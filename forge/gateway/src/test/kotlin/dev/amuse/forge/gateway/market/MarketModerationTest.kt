package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.ModerationFilter
import dev.amuse.forge.contract.ModerationQueue
import dev.amuse.forge.contract.PublishListingResponse
import dev.amuse.forge.contract.ReportReason
import dev.amuse.forge.gateway.ForgeTokens
import dev.amuse.forge.gateway.JobStore
import dev.amuse.forge.gateway.forgeModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketModerationTest {

    private val tokens = ForgeTokens(client = "client-secret", worker = "worker-secret", admin = "admin-secret")
    private fun newDir(): File = Files.createTempDirectory("market-moderation").toFile()

    /** A store with one listing hidden by two networks and one listing reported once, still listed. */
    private suspend fun seeded(store: MarketStore): Pair<PublishListingResponse, PublishListingResponse> {
        val hidden = store.publish(MarketFixtures.publishSkill(title = "Shady"))
        store.report(hidden.listingId, "device-aaaaaaaa", "ip-1", ReportReason.PROMPT_INJECTION, "asks for my password")
        store.report(hidden.listingId, "device-bbbbbbbb", "ip-2", ReportReason.HARMFUL)
        val reported = store.publish(MarketFixtures.publishSkill(title = "Maybe"))
        store.report(reported.listingId, "device-cccccccc", "ip-3", ReportReason.SPAM)
        store.publish(MarketFixtures.publishSkill(title = "Clean"))
        return hidden to reported
    }

    @Test
    fun `the queue shows reported listings, most networks first, without who reported`() = runBlocking<Unit> {
        val store = MarketStore(newDir(), hideAfterReports = 2)
        val (hidden, reported) = seeded(store)

        val queue = store.moderationQueue(ModerationFilter.REPORTED)
        assertEquals(listOf(hidden.listingId, reported.listingId), queue.items.map { it.listing.listingId })
        val first = queue.items.first()
        assertEquals(ListingStatus.HIDDEN, first.listing.status)
        assertEquals(2, first.reportNetworks)
        assertEquals("asks for my password", first.reports.first().note)

        assertEquals(listOf(hidden.listingId), store.moderationQueue(ModerationFilter.HIDDEN).items.map { it.listing.listingId })
    }

    @Test
    fun `restore lists it again and clears the reports so it is not hidden again at once`() = runBlocking<Unit> {
        val store = MarketStore(newDir(), hideAfterReports = 2)
        val (hidden, _) = seeded(store)

        assertTrue(store.restore(hidden.listingId))
        assertEquals(ListingStatus.LISTED, assertNotNull(store.get(hidden.listingId)).listing.status)
        store.report(hidden.listingId, "device-dddddddd", "ip-4", ReportReason.SPAM)
        assertNotNull(store.get(hidden.listingId), "one new report must not re-hide a restored listing")
        assertFalse(store.restore("lst_000000000000"))
    }

    @Test
    fun `hide takes a listing down by hand, remove deletes it from disk`() = runBlocking<Unit> {
        val dir = newDir()
        val store = MarketStore(dir)
        val p = store.publish(MarketFixtures.publishSkill())

        assertTrue(store.hide(p.listingId))
        assertNull(store.get(p.listingId))
        assertEquals(listOf(p.listingId), store.moderationQueue(ModerationFilter.HIDDEN).items.map { it.listing.listingId })

        assertTrue(store.remove(p.listingId))
        assertFalse(File(dir, "market/${p.listingId}").exists())
        assertNull(MarketStore(dir).get(p.listingId), "gone after a restart too")
        assertFalse(store.remove(p.listingId))
    }

    private fun ApplicationTestBuilder.start(store: MarketStore): HttpClient {
        val dir = newDir()
        application { forgeModule(JobStore(dir), tokens, longPollMaxSeconds = 1, market = store) }
        return createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    @Test
    fun `moderation needs the admin token`() = testApplication {
        val client = start(MarketStore(newDir()))
        listOf(null, tokens.client, tokens.worker).forEach { token ->
            val response = client.get(MarketRoutes.ADMIN_LISTINGS) { token?.let { bearerAuth(it) } }
            assertEquals(HttpStatusCode.Unauthorized, response.status, "token $token")
        }
        assertEquals(HttpStatusCode.OK, client.get(MarketRoutes.ADMIN_LISTINGS) { bearerAuth(tokens.admin) }.status)
    }

    @Test
    fun `queue, restore, hide and remove over http`() = testApplication {
        val store = MarketStore(newDir(), hideAfterReports = 2)
        val (hidden, reported) = runBlocking { seeded(store) }
        val client = start(store)

        val queue = client.get("${MarketRoutes.ADMIN_LISTINGS}?filter=hidden") { bearerAuth(tokens.admin) }.body<ModerationQueue>()
        assertEquals(listOf(hidden.listingId), queue.items.map { it.listing.listingId })
        assertEquals(HttpStatusCode.BadRequest, client.get("${MarketRoutes.ADMIN_LISTINGS}?filter=everything") { bearerAuth(tokens.admin) }.status)

        assertEquals(HttpStatusCode.NoContent, client.post(MarketRoutes.adminRestore(hidden.listingId)) { bearerAuth(tokens.admin) }.status)
        assertEquals(HttpStatusCode.OK, client.get(MarketRoutes.listing(hidden.listingId)).status)

        assertEquals(HttpStatusCode.NoContent, client.post(MarketRoutes.adminHide(reported.listingId)) { bearerAuth(tokens.admin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(MarketRoutes.listing(reported.listingId)).status)

        assertEquals(HttpStatusCode.NoContent, client.delete(MarketRoutes.adminListing(reported.listingId)) { bearerAuth(tokens.admin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.delete(MarketRoutes.adminListing(reported.listingId)) { bearerAuth(tokens.admin) }.status)
        assertEquals(HttpStatusCode.NotFound, client.post(MarketRoutes.adminRestore("..%2Fconfig")) { bearerAuth(tokens.admin) }.status)
    }
}
