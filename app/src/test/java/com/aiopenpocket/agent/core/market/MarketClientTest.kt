package com.aiopenpocket.agent.core.market

// @trace skill-from-the-market-in-the-agent-builder-acd66a

import com.aiopenpocket.agent.core.forge.ForgeGatewayConfig
import com.aiopenpocket.agent.core.forge.ForgeResult
import com.aiopenpocket.i18n.AppLanguage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * [MarketClient] against a fake engine: the public calls go out without a token, the documented
 * answers are read (`docs/market-app-integration.md`), and no failure escapes as an exception. JSON
 * shapes are written from the gateway contract (`forge/contract/.../MarketProtocol.kt`). Robolectric
 * for `Log`, as in `ScreenForgeClientTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MarketClientTest {

  @Before
  fun english() {
    AppLanguage.code = AppLanguage.EN
  }

  @After
  fun backToDefault() {
    AppLanguage.code = AppLanguage.RU
  }

  private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

  private fun client(engine: MockEngine, base: String = "http://forge.test") =
    MarketClient(engine) { ForgeGatewayConfig(base, token = "") }

  private fun answering(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    MockEngine { respond(body, status, jsonHeaders) }

  private fun <T> ok(result: ForgeResult<T>): T {
    assertTrue("expected Ok, got $result", result is ForgeResult.Ok)
    return (result as ForgeResult.Ok).value
  }

  private fun failed(result: ForgeResult<*>): String {
    assertTrue("expected Failed, got $result", result is ForgeResult.Failed)
    return (result as ForgeResult.Failed).message
  }

  private fun says(message: String, ru: String, en: String) {
    assertTrue(message, message.contains(ru) || message.contains(en))
  }

  private val page = """
    {"items":[{"listingId":"lst_54b6a169976f","kind":"skill","title":"Water coach","summary":"Counts glasses",
               "tags":["habits"],"publisherName":"Uros","latestVersion":2,"installs":7,"status":"listed",
               "createdAt":1,"updatedAt":2,"coverUrl":"/v1/market/listings/lst_54b6a169976f/cover","safety":"caution",
               "translation":{"lang":"en","title":"x","summary":"y"}}],
     "total":1,"page":1,"limit":20}
  """.trimIndent()

  // --- search --------------------------------------------------------------------------------------

  @Test
  fun `search asks for skills only, with the query and paging, and without a token`() = runTest {
    var method: HttpMethod? = null
    var url = ""
    var auth: String? = "unset"
    val engine = MockEngine { request ->
      method = request.method
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      respond(page, HttpStatusCode.OK, jsonHeaders)
    }

    val result = ok(client(engine).searchSkills("water", page = 2, limit = 10))

    assertEquals(HttpMethod.Get, method)
    assertEquals("http://forge.test/v1/market/listings?kind=skill&q=water&page=2&limit=10", url)
    assertNull("public route, no bearer", auth)
    assertEquals(1, result.total)
    val card = result.items.single()
    assertEquals("lst_54b6a169976f", card.listingId)
    assertEquals(MarketListingKind.SKILL, card.kind)
    assertEquals(MarketReviewVerdict.CAUTION, card.safety)
    assertEquals("/v1/market/listings/lst_54b6a169976f/cover", card.coverUrl)
    assertEquals(7, card.installs)
  }

  @Test
  fun `a blank query is left out of the URL`() = runTest {
    var url = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      respond("""{"items":[],"total":0,"page":1,"limit":20}""", HttpStatusCode.OK, jsonHeaders)
    }

    ok(client(engine).searchSkills("   "))

    assertEquals("http://forge.test/v1/market/listings?kind=skill&page=1&limit=20", url)
  }

  @Test
  fun `an empty address in Settings means the public gateway`() = runTest {
    var url = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      respond("""{"items":[],"total":0,"page":1,"limit":20}""", HttpStatusCode.OK, jsonHeaders)
    }

    ok(client(engine, base = "  ").searchSkills(null))

    assertTrue(url, url.startsWith(MarketClient.PUBLIC_BASE_URL + "/v1/market/listings"))
  }

  @Test
  fun `a cover path becomes an absolute URL on the gateway`() = runTest {
    val client = client(MockEngine { respond("", HttpStatusCode.OK) }, base = "http://forge.test/")

    assertEquals("http://forge.test/v1/market/listings/x/cover", client.absoluteUrl("/v1/market/listings/x/cover"))
    assertEquals("https://cdn.example/a.png", client.absoluteUrl("https://cdn.example/a.png"))
  }

  // --- listing -------------------------------------------------------------------------------------

  @Test
  fun `listing reads the skill package and the review, and skips the agent field`() = runTest {
    val body = """
      {"listing":{"listingId":"lst_1","kind":"skill","title":"Water coach","summary":"Counts glasses","tags":[],
                  "publisherName":"Uros","latestVersion":1,"installs":0,"status":"listed","createdAt":1,"updatedAt":1},
       "version":{"version":1,"changelog":null,"sha256":"9c1e","agent":null,
                  "skill":{"skillId":"water-coach","name":"Water coach","description":"Tracks glasses",
                           "whenToUse":"When drinking comes up","instructionsMarkdown":"# Water coach"},
                  "createdAt":1},
       "review":{"version":1,"verdict":"safe","summary":"Counts water, nothing else.","capabilities":["Counts glasses"],
                 "concerns":[{"kind":"heuristic","quote":"password","explanation":"Mentions credentials"}],
                 "model":"muse-spark-1.3","reviewedAt":5}}
    """.trimIndent()
    var url = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      respond(body, HttpStatusCode.OK, jsonHeaders)
    }

    val detail = ok(client(engine).listing("lst_1"))

    assertEquals("http://forge.test/v1/market/listings/lst_1", url)
    assertEquals("water-coach", detail.version.skill?.skillId)
    assertEquals("# Water coach", detail.version.skill?.instructionsMarkdown)
    assertEquals(MarketReviewVerdict.SAFE, detail.review?.verdict)
    assertEquals(listOf("Counts glasses"), detail.review?.capabilities)
    assertEquals("Mentions credentials", detail.review?.concerns?.single()?.explanation)
  }

  @Test
  fun `a listing that is gone is a sentence, not an exception`() = runTest {
    val message = failed(client(answering("No such listing", HttpStatusCode.NotFound)).listing("lst_gone"))

    says(message, "больше нет", "no longer on the market")
  }

  @Test
  fun `a 503 says the market is off on this server`() = runTest {
    val message = failed(client(answering("off", HttpStatusCode.ServiceUnavailable)).searchSkills(null))

    says(message, "выключен", "off on this server")
  }

  @Test
  fun `a 429 asks to try again later`() = runTest {
    val message = failed(client(answering("slow down", HttpStatusCode.TooManyRequests)).searchSkills(null))

    says(message, "позже", "try again")
  }

  @Test
  fun `a network failure is a sentence too`() = runTest {
    val message = failed(client(MockEngine { throw IOException("connection refused") }).searchSkills(null))

    says(message, "недоступен", "unreachable")
    assertTrue(message, message.contains("connection refused"))
  }

  @Test
  fun `garbage from the server does not crash the caller`() = runTest {
    val message = failed(client(answering("<html>oops</html>")).searchSkills(null))

    says(message, "непонятное", "unexpected")
  }

  // --- install -------------------------------------------------------------------------------------

  @Test
  fun `install posts the installId as JSON and reads a 204 as success`() = runTest {
    var method: HttpMethod? = null
    var url = ""
    var contentType: ContentType? = null
    var body = ""
    val engine = MockEngine { request ->
      method = request.method
      url = request.url.toString()
      val content = request.body as TextContent
      contentType = content.contentType
      body = content.text
      respond("", HttpStatusCode.NoContent)
    }

    ok(client(engine).install("lst_1", "3f1c8e2a-0000-4000-8000-000000000001"))

    assertEquals(HttpMethod.Post, method)
    assertEquals("http://forge.test/v1/market/listings/lst_1/install", url)
    assertEquals(ContentType.Application.Json, contentType?.withoutParameters())
    val sent = Json.parseToJsonElement(body).jsonObject
    assertEquals(setOf("installId"), sent.keys)
    assertEquals("3f1c8e2a-0000-4000-8000-000000000001", sent.getValue("installId").jsonPrimitive.content)
  }
}
