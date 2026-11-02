package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.InstallRequest
import dev.amuse.forge.contract.ListingDetail
import dev.amuse.forge.contract.ListingPage
import dev.amuse.forge.contract.MarketIssues
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.PublishListingRequest
import dev.amuse.forge.contract.PublishListingResponse
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.contract.ReportReason
import dev.amuse.forge.contract.ReportRequest
import dev.amuse.forge.contract.UpdateListingRequest
import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.gateway.ForgeTokens
import dev.amuse.forge.gateway.JobStore
import dev.amuse.forge.gateway.forgeModule
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketRoutesTest {

    private val tokens = ForgeTokens(client = "client-secret", worker = "worker-secret")

    private fun ApplicationTestBuilder.start(limits: MarketLimits = MarketLimits()): HttpClient {
        val dir = Files.createTempDirectory("market-routes").toFile()
        val market = MarketStore(dir, hideAfterReports = 2)
        application { forgeModule(JobStore(dir), tokens, longPollMaxSeconds = 1, market = market, marketLimits = limits) }
        return createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private suspend fun HttpClient.publish(request: PublishListingRequest = MarketFixtures.publishAgent()): HttpResponse =
        post(MarketRoutes.LISTINGS) {
            bearerAuth(tokens.client)
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.published(request: PublishListingRequest = MarketFixtures.publishAgent()): PublishListingResponse {
        val response = publish(request)
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    @Test
    fun `anyone browses, installs and reports, only the client token publishes`() = testApplication {
        val client = start()
        val p = client.published()
        assertEquals(HttpStatusCode.OK, client.get(MarketRoutes.LISTINGS).status)
        assertEquals(HttpStatusCode.OK, client.get(MarketRoutes.listing(p.listingId)).status)
        assertEquals(HttpStatusCode.OK, client.get(MarketRoutes.version(p.listingId, 1)).status)
        val install = client.post(MarketRoutes.install(p.listingId)) {
            contentType(ContentType.Application.Json)
            setBody(InstallRequest("device-aaaaaaaa"))
        }
        assertEquals(HttpStatusCode.NoContent, install.status)
        val report = client.post(MarketRoutes.report(p.listingId)) {
            contentType(ContentType.Application.Json)
            setBody(ReportRequest("device-aaaaaaaa", ReportReason.SPAM))
        }
        assertEquals(HttpStatusCode.NoContent, report.status)

        val anonymous = client.post(MarketRoutes.LISTINGS) {
            contentType(ContentType.Application.Json)
            setBody(MarketFixtures.publishAgent())
        }
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        val asWorker = client.post(MarketRoutes.LISTINGS) {
            bearerAuth(tokens.worker)
            contentType(ContentType.Application.Json)
            setBody(MarketFixtures.publishAgent())
        }
        assertEquals(HttpStatusCode.Unauthorized, asWorker.status)
        val patch = client.patch(MarketRoutes.listing(p.listingId)) {
            header(MarketRoutes.EDIT_KEY_HEADER, p.editKey)
            contentType(ContentType.Application.Json)
            setBody(UpdateListingRequest(title = "x"))
        }
        assertEquals(HttpStatusCode.Unauthorized, patch.status, "the edit key alone is not enough")
    }

    @Test
    fun `installs are rate limited per client`() = testApplication {
        val client = start(MarketLimits(installsPerHour = 2))
        val p = client.published()
        val statuses = listOf("device-aaaaaaaa", "device-bbbbbbbb", "device-cccccccc").map { device ->
            client.post(MarketRoutes.install(p.listingId)) {
                contentType(ContentType.Application.Json)
                setBody(InstallRequest(device))
            }.status
        }
        assertEquals(listOf(HttpStatusCode.NoContent, HttpStatusCode.NoContent, HttpStatusCode.TooManyRequests), statuses)
    }

    @Test
    fun `publish, find, open, new version, edit`() = testApplication {
        val client = start()
        val p = client.published()

        val page = client.get("${MarketRoutes.LISTINGS}?kind=agent&q=hydration") { bearerAuth(tokens.client) }.body<ListingPage>()
        assertEquals(listOf(p.listingId), page.items.map { it.listingId })

        val detail = client.get(MarketRoutes.listing(p.listingId)) { bearerAuth(tokens.client) }.body<ListingDetail>()
        assertEquals("Hydration buddy", detail.version.agent?.name)

        val v2 = client.post(MarketRoutes.versions(p.listingId)) {
            bearerAuth(tokens.client)
            header(MarketRoutes.EDIT_KEY_HEADER, p.editKey)
            contentType(ContentType.Application.Json)
            setBody(PublishVersionRequest(changelog = "more water", agent = MarketFixtures.agent().copy(goalMaxRounds = 5)))
        }
        assertEquals(HttpStatusCode.Created, v2.status)
        assertEquals(2, v2.body<ListingDetail>().version.version)
        val v1 = client.get(MarketRoutes.version(p.listingId, 1)) { bearerAuth(tokens.client) }
        assertEquals(HttpStatusCode.OK, v1.status)

        val renamed = client.patch(MarketRoutes.listing(p.listingId)) {
            bearerAuth(tokens.client)
            header(MarketRoutes.EDIT_KEY_HEADER, p.editKey)
            contentType(ContentType.Application.Json)
            setBody(UpdateListingRequest(title = "Water buddy", status = ListingStatus.UNLISTED))
        }
        assertEquals(HttpStatusCode.OK, renamed.status)
        assertEquals("Water buddy", renamed.body<ListingDetail>().listing.title)
    }

    @Test
    fun `validation answers 400 with every issue`() = testApplication {
        val client = start()
        val response = client.publish(MarketFixtures.publishSkill(title = "", tags = listOf("Bad Tag")))
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val issues = response.body<MarketIssues>().issues.map { it.field }
        assertTrue("title" in issues)
        assertTrue("tags" in issues)
    }

    @Test
    fun `edits need the right key, unknown ids are 404`() = testApplication {
        val client = start()
        val p = client.published()
        val noKey = client.patch(MarketRoutes.listing(p.listingId)) {
            bearerAuth(tokens.client)
            contentType(ContentType.Application.Json)
            setBody(UpdateListingRequest(title = "x"))
        }
        assertEquals(HttpStatusCode.Forbidden, noKey.status)
        val wrongKey = client.patch(MarketRoutes.listing(p.listingId)) {
            bearerAuth(tokens.client)
            header(MarketRoutes.EDIT_KEY_HEADER, "nope")
            contentType(ContentType.Application.Json)
            setBody(UpdateListingRequest(title = "x"))
        }
        assertEquals(HttpStatusCode.Forbidden, wrongKey.status)
        assertEquals(HttpStatusCode.NotFound, client.get(MarketRoutes.listing("lst_000000000000")) { bearerAuth(tokens.client) }.status)
        assertEquals(HttpStatusCode.NotFound, client.get(MarketRoutes.listing("..%2F..%2Fetc")) { bearerAuth(tokens.client) }.status)
    }

    @Test
    fun `too big a body is refused before it is read`() = testApplication {
        val client = start(MarketLimits(maxBodyBytes = 1_000))
        val big = MarketFixtures.publishSkill(skill = MarketFixtures.skill().copy(instructionsMarkdown = "x".repeat(5_000)))
        assertEquals(HttpStatusCode.PayloadTooLarge, client.publish(big).status)
    }

    @Test
    fun `publishing is rate limited per client`() = testApplication {
        val client = start(MarketLimits(publishPerHour = 2))
        client.published()
        client.published()
        assertEquals(HttpStatusCode.TooManyRequests, client.publish().status)
    }

    @Test
    fun `install counts, reports from distinct networks hide`() = testApplication {
        val client = start(MarketLimits(trustFlyClientIp = true))
        val p = client.published()
        val install = client.post(MarketRoutes.install(p.listingId)) {
            bearerAuth(tokens.client)
            contentType(ContentType.Application.Json)
            setBody(InstallRequest("device-aaaaaaaa"))
        }
        assertEquals(HttpStatusCode.NoContent, install.status)
        assertEquals(1, client.get(MarketRoutes.listing(p.listingId)) { bearerAuth(tokens.client) }.body<ListingDetail>().listing.installs)

        suspend fun report(device: String, ip: String) {
            val response = client.post(MarketRoutes.report(p.listingId)) {
                header("Fly-Client-IP", ip)
                contentType(ContentType.Application.Json)
                setBody(ReportRequest(device, ReportReason.PROMPT_INJECTION))
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
        }
        report("device-aaaaaaaa", "203.0.113.1")
        report("device-bbbbbbbb", "203.0.113.1")
        assertEquals(HttpStatusCode.OK, client.get(MarketRoutes.listing(p.listingId)).status, "one network is one reporter")
        report("device-cccccccc", "203.0.113.2")
        assertEquals(HttpStatusCode.NotFound, client.get(MarketRoutes.listing(p.listingId)) { bearerAuth(tokens.client) }.status)
    }

    @Test
    fun `the share page needs no token, escapes the title and links into the app`() = testApplication {
        val client = start()
        val p = client.published(MarketFixtures.publishAgent(title = "<script>alert(1)</script>"))
        val page = client.get(MarketRoutes.share(p.listingId))
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertFalse("<script>alert" in html)
        assertTrue("&lt;script&gt;" in html)
        assertTrue(MarketRoutes.deepLink(p.listingId) in html)
        assertEquals(HttpStatusCode.NotFound, client.get(MarketRoutes.share("lst_000000000000")).status)
    }
}
