package dev.amuse.forge.gateway.market.ai

import dev.amuse.forge.contract.ListingDetail
import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.MatchRequest
import dev.amuse.forge.contract.MatchResponse
import dev.amuse.forge.contract.ModerationFilter
import dev.amuse.forge.contract.PublishListingRequest
import dev.amuse.forge.contract.PublishListingResponse
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.contract.ReviewVerdict
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
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketAiFlowTest {

    private val tokens = ForgeTokens(client = "client-secret", worker = "worker-secret", admin = "admin-secret")

    /** Answers a review with [verdict] and a match with the listings whose title contains "water". */
    private fun models(verdict: String = "safe") = FakeMetaModels(answer = { system, user ->
        if (system.contains("security reviewer")) {
            """{"verdict":"$verdict","summary":"Reminds you to drink water.","capabilities":["Notifications"],"concerns":[]}"""
        } else {
            val ids = Regex("""id=(lst_[0-9a-f]{12}) title=[^\n]*[Ww]ater""").findAll(user).map { it.groupValues[1] }.toList()
            """{"matches":[${(ids + "lst_ffffffffffff").joinToString(",") { """{"listingId":"$it","reason":"Tracks water"}""" }}]}"""
        }
    })

    private class Setup(val client: HttpClient, val store: MarketStore, val runner: QueuedTaskRunner, val models: FakeMetaModels)

    private fun ApplicationTestBuilder.start(models: FakeMetaModels? = models(), limits: MarketAiLimits = MarketAiLimits()): Setup {
        val dir = Files.createTempDirectory("market-ai").toFile()
        val store = MarketStore(dir)
        val runner = QueuedTaskRunner()
        val ai = models?.let { MarketAi.create(store, it, runner, limits) }
        application {
            forgeModule(JobStore(dir), tokens, longPollMaxSeconds = 1, market = store, marketAi = ai, publicBaseUrl = "https://forge.example")
        }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return Setup(client, store, runner, models ?: FakeMetaModels())
    }

    private suspend fun HttpClient.publish(request: PublishListingRequest = MarketFixtures.publishAgent()): PublishListingResponse =
        post(MarketRoutes.LISTINGS) {
            bearerAuth(tokens.client)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    private suspend fun HttpClient.detail(id: String): HttpResponse = get(MarketRoutes.listing(id))

    private suspend fun HttpClient.match(query: String, kind: ListingKind? = null): HttpResponse =
        post(MarketRoutes.MATCH) {
            contentType(ContentType.Application.Json)
            setBody(MatchRequest(query, kind))
        }

    @Test
    fun `publish answers at once, review and cover land in the background`() = testApplication {
        val s = start()
        val p = s.client.publish()
        val before = s.client.detail(p.listingId).body<ListingDetail>()
        assertNull(before.review, "the person does not wait for the model")
        assertNull(before.listing.coverUrl)
        assertEquals(1, s.runner.pending)

        s.runner.runAll()
        val after = s.client.detail(p.listingId).body<ListingDetail>()
        val review = assertNotNull(after.review)
        assertEquals(ReviewVerdict.SAFE, review.verdict)
        assertEquals(ReviewVerdict.SAFE, after.listing.safety)
        assertEquals(MarketRoutes.cover(p.listingId), after.listing.coverUrl)
        assertTrue(s.models.imagePrompts.single().contains("Hydration buddy"))

        val cover = s.client.get(MarketRoutes.cover(p.listingId))
        assertEquals(HttpStatusCode.OK, cover.status)
        assertEquals("PNGBYTES", String(cover.readRawBytes()))
        assertEquals("image/png", cover.headers[HttpHeaders.ContentType])
    }

    @Test
    fun `the share page shows the cover to link previews`() = testApplication {
        val s = start()
        val p = s.client.publish()
        s.runner.runAll()
        val html = s.client.get(MarketRoutes.share(p.listingId)).bodyAsText()
        assertTrue("""<meta property="og:image" content="https://forge.example${MarketRoutes.cover(p.listingId)}">""" in html)
    }

    @Test
    fun `a blocked package is hidden and waits in the moderation queue with its review`() = testApplication {
        val s = start(models("block"))
        val p = s.client.publish()
        s.runner.runAll()
        assertEquals(HttpStatusCode.NotFound, s.client.detail(p.listingId).status)
        val queued = runBlocking { s.store.moderationQueue(ModerationFilter.HIDDEN) }.items.single()
        assertEquals(ListingStatus.HIDDEN, queued.listing.status)
        assertEquals(ReviewVerdict.BLOCK, queued.review?.verdict)
    }

    @Test
    fun `a new version is reviewed again, and the cover is not redrawn`() = testApplication {
        val s = start()
        val p = s.client.publish()
        s.runner.runAll()
        s.client.post(MarketRoutes.versions(p.listingId)) {
            bearerAuth(tokens.client)
            header(MarketRoutes.EDIT_KEY_HEADER, p.editKey)
            contentType(ContentType.Application.Json)
            setBody(PublishVersionRequest(agent = MarketFixtures.agent().copy(goalMaxRounds = 5)))
        }
        assertNull(s.client.detail(p.listingId).body<ListingDetail>().review, "the old review does not vouch for the new version")
        s.runner.runAll()
        assertEquals(2, s.client.detail(p.listingId).body<ListingDetail>().review?.version)
        assertEquals(1, s.models.imagePrompts.size)
    }

    @Test
    fun `a review for an older version is dropped`() = runBlocking<Unit> {
        val store = MarketStore(Files.createTempDirectory("market-ai-stale").toFile())
        val p = store.publish(MarketFixtures.publishSkill())
        store.addVersion(p.listingId, p.editKey, PublishVersionRequest(skill = MarketFixtures.skill()))
        val stale = dev.amuse.forge.contract.ListingReview(1, ReviewVerdict.BLOCK, "old", model = "m", reviewedAt = 1)
        assertEquals(false, store.applyReview(p.listingId, stale))
        assertNotNull(store.get(p.listingId))
    }

    @Test
    fun `unfinished work is picked up again after a restart`() = runBlocking<Unit> {
        val dir = Files.createTempDirectory("market-ai-resume").toFile()
        val p = MarketStore(dir).publish(MarketFixtures.publishSkill())
        val store = MarketStore(dir)
        val runner = QueuedTaskRunner()
        MarketAi.create(store, models(), runner).enricher.resumePending()
        runner.runAll()
        assertNotNull(store.get(p.listingId)?.review)
        assertNotNull(store.get(p.listingId)?.listing?.coverUrl)
    }

    @Test
    fun `smart search returns only real listings, with a reason`() = testApplication {
        val s = start()
        val water = s.client.publish(MarketFixtures.publishSkill(title = "Water coach"))
        s.client.publish(MarketFixtures.publishSkill(title = "Budget helper").copy(summary = "Tracks spending"))
        val response = s.client.match("remind me to drink")
        assertEquals(HttpStatusCode.OK, response.status)
        val items = response.body<MatchResponse>().items
        assertEquals(listOf(water.listingId), items.map { it.listing.listingId }, "the made-up id is dropped")
        assertEquals("Tracks water", items.single().reason)
    }

    @Test
    fun `the same question is answered from cache until the catalog changes`() = testApplication {
        val s = start()
        s.client.publish(MarketFixtures.publishSkill(title = "Water coach"))
        s.client.match("remind me to drink")
        s.client.match("  Remind me to DRINK ")
        assertEquals(1, s.models.chats.size)
        s.client.publish(MarketFixtures.publishSkill(title = "Water log"))
        s.client.match("remind me to drink")
        assertEquals(2, s.models.chats.size)
    }

    @Test
    fun `smart search is bounded and rate limited, and says so when AI is off`() = testApplication {
        val s = start(limits = MarketAiLimits(matchesPerHour = 1))
        assertEquals(HttpStatusCode.BadRequest, s.client.match(" ").status)
        assertEquals(HttpStatusCode.BadRequest, s.client.match("x".repeat(MarketAiLimits().maxQueryChars + 1)).status)
        s.client.match("first")
        assertEquals(HttpStatusCode.TooManyRequests, s.client.match("second").status)
    }

    @Test
    fun `without a Meta key the market still works, smart search answers 503`() = testApplication {
        val s = start(models = null)
        val p = s.client.publish()
        assertEquals(HttpStatusCode.OK, s.client.detail(p.listingId).status)
        assertEquals(HttpStatusCode.ServiceUnavailable, s.client.match("water").status)
    }
}
