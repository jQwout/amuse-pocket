package dev.amuse.forge.gateway.market.ai

import dev.amuse.forge.contract.ListingDetail
import dev.amuse.forge.contract.ListingPage
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.PublishListingResponse
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.gateway.ForgeTokens
import dev.amuse.forge.gateway.JobStore
import dev.amuse.forge.gateway.ai.FakeMetaModels
import dev.amuse.forge.gateway.ai.QueuedTaskRunner
import dev.amuse.forge.gateway.forgeModule
import dev.amuse.forge.gateway.market.MarketFixtures
import dev.amuse.forge.gateway.market.MarketStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MarketTranslationFlowTest {

    private val tokens = ForgeTokens(client = "client-secret", worker = "worker-secret", admin = "admin-secret")

    private class Setup(val client: HttpClient, val runner: QueuedTaskRunner, val models: FakeMetaModels) {
        /** Translation calls only: the review and the cover run on the same fake. */
        val translations get() = models.chats.count { "translator" in it.first }
    }

    private fun ApplicationTestBuilder.start(
        models: FakeMetaModels? = translatingModels(),
        limits: MarketAiLimits = MarketAiLimits(),
        dir: File = Files.createTempDirectory("market-tr").toFile(),
    ): Setup {
        val store = MarketStore(dir)
        val runner = QueuedTaskRunner()
        val ai = models?.let { MarketAi.create(store, it, runner, limits) }
        application { forgeModule(JobStore(dir), tokens, longPollMaxSeconds = 1, market = store, marketAi = ai) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return Setup(client, runner, models ?: FakeMetaModels())
    }

    private suspend fun HttpClient.publish(title: String = "Водный друг"): PublishListingResponse =
        post(MarketRoutes.LISTINGS) {
            bearerAuth(tokens.client)
            contentType(ContentType.Application.Json)
            setBody(MarketFixtures.publishAgent(title = title))
        }.body()

    private suspend fun HttpClient.detail(id: String, lang: String? = "en"): ListingDetail =
        get(MarketRoutes.listing(id) + (lang?.let { "?lang=$it" } ?: "")).body()

    @Test
    fun `a detail in another language comes with its translation, once`() = testApplication {
        val s = start()
        val p = s.client.publish()
        val first = s.client.detail(p.listingId)
        assertEquals("Водный друг", first.listing.title, "the original stays the original")
        assertEquals("EN:Водный друг", assertNotNull(first.translation).title)
        s.client.detail(p.listingId)
        assertEquals(1, s.translations)
        assertNull(s.client.detail(p.listingId, lang = null).translation)
    }

    @Test
    fun `the cache survives a restart, and a new version is translated again`() = testApplication {
        val dir = Files.createTempDirectory("market-tr-restart").toFile()
        val s = start(dir = dir)
        val p = s.client.publish()
        s.client.detail(p.listingId)
        s.client.post(MarketRoutes.versions(p.listingId)) {
            bearerAuth(tokens.client)
            header(MarketRoutes.EDIT_KEY_HEADER, p.editKey)
            contentType(ContentType.Application.Json)
            setBody(PublishVersionRequest(agent = MarketFixtures.agent().copy(systemPrompt = "Новая версия")))
        }
        assertEquals("EN:Новая версия", s.client.detail(p.listingId).translation?.agent?.systemPrompt)
        assertEquals(2, s.translations)
    }

    @Test
    fun `search hands back what is translated and translates the rest in the background`() = testApplication {
        val s = start()
        s.client.publish("Водный друг")
        s.client.publish("Бюджет")
        val before = s.client.get("${MarketRoutes.LISTINGS}?lang=en").body<ListingPage>()
        assertEquals(listOf(null, null), before.items.map { it.translation }, "search never waits for the model")

        s.runner.runAll()
        val after = s.client.get("${MarketRoutes.LISTINGS}?lang=en").body<ListingPage>()
        assertEquals(setOf("EN:Водный друг", "EN:Бюджет"), after.items.map { it.translation?.title }.toSet())
        s.client.get("${MarketRoutes.LISTINGS}?lang=en")
        s.runner.runAll()
        assertEquals(2, s.translations, "each listing translated once")
    }

    @Test
    fun `a bad language is refused`() = testApplication {
        val s = start()
        val p = s.client.publish()
        assertEquals(HttpStatusCode.BadRequest, s.client.get(MarketRoutes.listing(p.listingId) + "?lang=english").status)
        assertEquals(HttpStatusCode.BadRequest, s.client.get("${MarketRoutes.LISTINGS}?lang=EN!").status)
    }

    @Test
    fun `over the rate limit the detail still answers, untranslated`() = testApplication {
        val s = start(limits = MarketAiLimits(translationsPerHour = 1))
        val a = s.client.publish("Первый")
        val b = s.client.publish("Второй")
        assertNotNull(s.client.detail(a.listingId).translation)
        val limited = s.client.get(MarketRoutes.listing(b.listingId) + "?lang=en")
        assertEquals(HttpStatusCode.OK, limited.status)
        assertNull(limited.body<ListingDetail>().translation)
    }

    @Test
    fun `without AI a lang parameter changes nothing`() = testApplication {
        val s = start(models = null)
        val p = s.client.publish()
        val detail = s.client.detail(p.listingId)
        assertEquals("Водный друг", detail.listing.title)
        assertNull(detail.translation)
    }
}
