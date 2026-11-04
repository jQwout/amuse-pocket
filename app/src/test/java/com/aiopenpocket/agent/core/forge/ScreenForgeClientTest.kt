package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import com.aiopenpocket.i18n.AppLanguage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * [ScreenForgeClient] against a fake engine: what goes to the gateway (path, Bearer header, camelCase
 * JSON), how every documented answer is read, and that no failure escapes as an exception. The JSON
 * shapes are written from the gateway contract (`forge/contract`), not recorded from a live gateway.
 * Robolectric for `Log`, as in the neighbouring client tests. The interface language is pinned to English
 * so the person-facing messages can be matched on words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenForgeClientTest {

  @Before
  fun english() {
    AppLanguage.code = AppLanguage.EN
  }

  @After
  fun backToDefault() {
    AppLanguage.code = AppLanguage.RU
  }

  private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

  private fun client(engine: MockEngine, base: String = "http://forge.test", token: String = "tok-123", metaKey: String = "LLM_person_key") =
    ScreenForgeClient(engine) { ForgeGatewayConfig(base, token, metaKey) }

  private fun answering(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    MockEngine { respond(body, status, jsonHeaders) }

  private val createRequest = ForgeCreateJobRequest(
    agentId = "agent-7",
    agentName = "Budget",
    agentDescription = "Tracks spending",
    request = "a chart of the week",
  )

  private fun failed(result: ForgeResult<*>): String {
    assertTrue("expected Failed, got $result", result is ForgeResult.Failed)
    return (result as ForgeResult.Failed).message
  }

  /**
   * The wording is not what these tests guard, and the `tr` switch is Compose snapshot state that a
   * different thread was seen reading as the Russian default (a few runs in ten) even with English set in
   * `@Before`. So a message is matched in both languages.
   */
  private fun says(message: String, ru: String, en: String) {
    assertTrue(message, message.contains(ru) || message.contains(en))
  }

  private fun <T> ok(result: ForgeResult<T>): T {
    assertTrue("expected Ok, got $result", result is ForgeResult.Ok)
    return (result as ForgeResult.Ok).value
  }

  // --- submit --------------------------------------------------------------------------------------

  @Test
  fun `submit posts the job with the bearer token and camelCase fields and reads the 202 job id`() = runTest {
    var method: HttpMethod? = null
    var url = ""
    var auth: String? = null
    var contentType: ContentType? = null
    var body = ""
    val engine = MockEngine { request ->
      method = request.method
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      val content = request.body as TextContent
      contentType = content.contentType
      body = content.text
      // An extra field the app does not know: it must be ignored, not fail the decode.
      respond("""{"jobId":"job-abc-1","futureField":true}""", HttpStatusCode.Accepted, jsonHeaders)
    }

    val result = client(engine).submit(createRequest.copy(previousJobId = "job-prev-0"))

    assertEquals("job-abc-1", ok(result))
    assertEquals(HttpMethod.Post, method)
    assertEquals("http://forge.test/v1/jobs", url)
    assertEquals("Bearer tok-123", auth)
    assertEquals(ContentType.Application.Json, contentType?.withoutParameters())
    val sent = Json.parseToJsonElement(body).jsonObject
    assertEquals(setOf("agentId", "agentName", "agentDescription", "request", "previousJobId"), sent.keys)
    assertEquals("agent-7", sent.getValue("agentId").jsonPrimitive.content)
    assertEquals("Budget", sent.getValue("agentName").jsonPrimitive.content)
    assertEquals("Tracks spending", sent.getValue("agentDescription").jsonPrimitive.content)
    assertEquals("a chart of the week", sent.getValue("request").jsonPrimitive.content)
    assertEquals("job-prev-0", sent.getValue("previousJobId").jsonPrimitive.content)
  }

  @Test
  fun `submit leaves previousJobId out when there is none`() = runTest {
    var body = ""
    val engine = MockEngine { request ->
      body = (request.body as TextContent).text
      respond("""{"jobId":"j"}""", HttpStatusCode.Accepted, jsonHeaders)
    }

    client(engine).submit(createRequest)

    assertFalse("previousJobId" in Json.parseToJsonElement(body).jsonObject)
  }

  @Test
  fun `a trailing slash or spaces in the base address do not double the slash`() = runTest {
    val urls = mutableListOf<String>()
    val engine = MockEngine { request ->
      urls += request.url.toString()
      respond("""{"jobId":"j"}""", HttpStatusCode.Accepted, jsonHeaders)
    }

    client(engine, base = "  http://forge.test/  ").submit(createRequest)

    assertEquals(listOf("http://forge.test/v1/jobs"), urls)
  }

  // --- status --------------------------------------------------------------------------------------

  @Test
  fun `status asks GET v1 jobs id with the bearer token`() = runTest {
    var method: HttpMethod? = null
    var url = ""
    var auth: String? = null
    val engine = MockEngine { request ->
      method = request.method
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      respond("""{"jobId":"job-77","status":"queued"}""", HttpStatusCode.OK, jsonHeaders)
    }

    val view = ok(client(engine).status("job-77"))

    assertEquals(HttpMethod.Get, method)
    assertEquals("http://forge.test/v1/jobs/job-77", url)
    assertEquals("Bearer tok-123", auth)
    assertEquals("job-77", view.jobId)
  }

  @Test
  fun `status parses every status string of the protocol`() = runTest {
    val expected = mapOf(
      "queued" to ForgeJobStatus.QUEUED,
      "accepted" to ForgeJobStatus.ACCEPTED,
      "needs_info" to ForgeJobStatus.NEEDS_INFO,
      "working" to ForgeJobStatus.WORKING,
      "done" to ForgeJobStatus.DONE,
      "failed" to ForgeJobStatus.FAILED,
    )
    for ((wire, status) in expected) {
      val view = ok(client(answering("""{"jobId":"j","status":"$wire"}""")).status("j"))
      assertEquals(wire, status, view.status)
    }
  }

  @Test
  fun `status carries the question, the message and the artifact`() = runTest {
    val body = """
      {"jobId":"job-1","agentId":"agent-7","status":"done","message":"compiled in 41s","question":"Which colour?",
       "artifact":{"url":"/v1/artifacts/job-1.ktx","sha256":"abc123","entryPoint":"WaterScreen","version":3,"sizeBytes":2048},
       "createdAt":1700000000000,"updatedAt":1700000005000}
    """.trimIndent()

    val view = ok(client(answering(body)).status("job-1"))

    assertEquals("agent-7", view.agentId)
    assertEquals("compiled in 41s", view.message)
    assertEquals("Which colour?", view.question)
    assertEquals(ForgeArtifact("/v1/artifacts/job-1.ktx", "abc123", "WaterScreen", 3, 2048L), view.artifact)
    assertEquals(1_700_000_005_000L, view.updatedAt)
  }

  @Test
  fun `status without optional fields leaves them null`() = runTest {
    val view = ok(client(answering("""{"jobId":"j","status":"working"}""")).status("j"))

    assertNull(view.question)
    assertNull(view.artifact)
    assertNull(view.message)
  }

  @Test
  fun `an unknown status string is a Failed, not a crash`() = runTest {
    val message = failed(client(answering("""{"jobId":"j","status":"teleporting"}""")).status("j"))

    says(message, "непонятное", "unexpected")
  }

  @Test
  fun `a body that is not JSON is a Failed, not a crash`() = runTest {
    failed(client(answering("<html>bad gateway</html>")).status("j"))
  }

  // --- answer --------------------------------------------------------------------------------------

  @Test
  fun `answer posts the text to v1 jobs id answer and returns the job view`() = runTest {
    var method: HttpMethod? = null
    var url = ""
    var auth: String? = null
    var body = ""
    val engine = MockEngine { request ->
      method = request.method
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      body = (request.body as TextContent).text
      respond("""{"jobId":"job-5","status":"queued"}""", HttpStatusCode.OK, jsonHeaders)
    }

    val view = ok(client(engine).answer("job-5", "Blue, please"))

    assertEquals(HttpMethod.Post, method)
    assertEquals("http://forge.test/v1/jobs/job-5/answer", url)
    assertEquals("Bearer tok-123", auth)
    assertEquals(setOf("text"), Json.parseToJsonElement(body).jsonObject.keys)
    assertEquals("Blue, please", Json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content)
    assertEquals(ForgeJobStatus.QUEUED, view.status)
  }

  // --- failures ------------------------------------------------------------------------------------

  @Test
  fun `401 and 403 tell the person to check the token in Settings`() = runTest {
    for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
      val message = failed(client(answering("""{"error":"nope"}""", status)).status("j"))
      says(message, "Настройках", "Settings")
      says(message, "токен", "token")
    }
  }

  @Test
  fun `the Russian message also points to the Settings`() = runTest {
    AppLanguage.code = AppLanguage.RU

    val message = failed(client(answering("", HttpStatusCode.Unauthorized)).status("j"))

    assertTrue(message, message.contains("Настройках"))
  }

  @Test
  fun `404 says the job is gone and quotes the gateway detail`() = runTest {
    val message = failed(client(answering("job expired", HttpStatusCode.NotFound)).status("j"))

    says(message, "не нашла эту задачу", "no longer has this job")
    assertTrue(message, message.contains("job expired"))
  }

  @Test
  fun `5xx is a Failed that names the status`() = runTest {
    for (code in listOf(500, 502, 503)) {
      val message = failed(client(answering("oops", HttpStatusCode.fromValue(code))).submit(createRequest))
      assertTrue("$code: $message", message.contains("HTTP $code"))
    }
  }

  @Test
  fun `another 4xx is a Failed that names the status and keeps the gateway detail`() = runTest {
    val message = failed(client(answering("request too long", HttpStatusCode.BadRequest)).submit(createRequest))

    assertTrue(message, message.contains("HTTP 400"))
    assertTrue(message, message.contains("request too long"))
  }

  @Test
  fun `a timeout becomes a Failed`() = runTest {
    val engine = MockEngine { throw HttpRequestTimeoutException("http://forge.test/v1/jobs/j", 30_000L) }

    val message = failed(client(engine).status("j"))

    says(message, "не ответила вовремя", "did not answer in time")
  }

  @Test
  fun `a network error becomes a Failed that points to the address`() = runTest {
    val engine = MockEngine { throw IOException("Connection refused") }

    val message = failed(client(engine).status("j"))

    assertTrue(message, message.contains("Connection refused"))
    says(message, "Настройках", "Settings")
  }

  @Test
  fun `cancellation is not swallowed`() = runTest {
    val engine = MockEngine { throw CancellationException("stop") }

    try {
      client(engine).status("j")
      fail("CancellationException must propagate")
    } catch (expected: CancellationException) {
      // The only exception the client lets out.
    }
  }

  // --- config --------------------------------------------------------------------------------------

  @Test
  fun `a blank address or token fails without touching the network`() = runTest {
    for ((base, token) in listOf("" to "tok", "http://forge.test" to "", "  " to "  ")) {
      var requests = 0
      val engine = MockEngine { requests++; respond("{}", HttpStatusCode.OK, jsonHeaders) }
      val c = client(engine, base, token)

      assertFalse(c.isConfigured())
      says(failed(c.submit(createRequest)), "адрес кузницы и токен", "forge address and token")
      says(failed(c.status("j")), "адрес кузницы и токен", "forge address and token")
      says(failed(c.answer("j", "x")), "адрес кузницы и токен", "forge address and token")
      says(failed(c.downloadBundle(ForgeArtifact("/a.ktx", "00", "E", 1))), "адрес кузницы и токен", "forge address and token")
      assertEquals("base='$base' token='$token'", 0, requests)
    }
  }

  @Test
  fun `submit and answer carry the person's own Meta key, and without one nothing leaves the phone`() = runTest {
    val keys = mutableListOf<String?>()
    val engine = MockEngine { request ->
      keys += request.headers[ScreenForgeClient.META_KEY_HEADER]
      if (request.url.encodedPath.endsWith("/answer")) {
        respond("""{"jobId":"j","agentId":"a","status":"queued","createdAt":1,"updatedAt":2}""", HttpStatusCode.OK, jsonHeaders)
      } else {
        respond("""{"jobId":"j"}""", HttpStatusCode.Accepted, jsonHeaders)
      }
    }
    ok(client(engine).submit(createRequest))
    ok(client(engine).answer("j", "Blue"))
    // Polling and the download only need the gateway token: the key goes where a model call starts.
    client(engine).status("j")
    assertEquals(listOf("LLM_person_key", "LLM_person_key", null), keys)

    var requests = 0
    val counting = MockEngine { requests++; respond("{}", HttpStatusCode.OK, jsonHeaders) }
    val keyless = client(counting, metaKey = "  ")
    says(failed(keyless.submit(createRequest)), "ключе Meta", "Meta key")
    says(failed(keyless.answer("j", "x")), "ключе Meta", "Meta key")
    assertEquals(0, requests)
    assertFalse(ForgeGatewayConfig("http://forge.test", "tok", "LLM_secret").toString().contains("LLM_secret"))
  }

  @Test
  fun `the config is read on every call so a change in Settings applies at once`() = runTest {
    val urls = mutableListOf<String>()
    val auths = mutableListOf<String?>()
    val engine = MockEngine { request ->
      urls += request.url.toString()
      auths += request.headers[HttpHeaders.Authorization]
      respond("""{"jobId":"j","status":"queued"}""", HttpStatusCode.OK, jsonHeaders)
    }
    var config = ForgeGatewayConfig("http://one.test", "tok-one")
    val c = ScreenForgeClient(engine) { config }

    c.status("j")
    config = ForgeGatewayConfig("http://two.test", "tok-two")
    c.status("j")

    assertEquals(listOf("http://one.test/v1/jobs/j", "http://two.test/v1/jobs/j"), urls)
    assertEquals(listOf("Bearer tok-one", "Bearer tok-two"), auths)
  }

  // --- downloadBundle ------------------------------------------------------------------------------

  private val bundle = ByteArray(64) { (it * 3 + 1).toByte() }
  private val bundleSha = ScreenForgeClient.sha256Hex(bundle)

  private fun artifact(url: String, sha: String = bundleSha) = ForgeArtifact(url, sha, "WaterScreen", 2, bundle.size.toLong())

  @Test
  fun `sha256Hex matches the known digest of abc`() {
    assertEquals(
      "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
      ScreenForgeClient.sha256Hex("abc".toByteArray()),
    )
  }

  @Test
  fun `a relative artifact url is joined to the base and the bytes come back when the checksum matches`() = runTest {
    var url = ""
    var auth: String? = null
    var method: HttpMethod? = null
    val engine = MockEngine { request ->
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      method = request.method
      respond(bundle, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
    }

    val bytes = ok(client(engine, base = "http://forge.test/").downloadBundle(artifact("/v1/artifacts/job-1.ktx")))

    assertArrayEquals(bundle, bytes)
    assertEquals(HttpMethod.Get, method)
    assertEquals("http://forge.test/v1/artifacts/job-1.ktx", url)
    assertEquals("Bearer tok-123", auth)
  }

  @Test
  fun `a relative url without a leading slash joins the same way`() = runTest {
    var url = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      respond(bundle, HttpStatusCode.OK)
    }

    ok(client(engine).downloadBundle(artifact("v1/artifacts/job-1.ktx")))

    assertEquals("http://forge.test/v1/artifacts/job-1.ktx", url)
  }

  @Test
  fun `an absolute artifact url is used as it is`() = runTest {
    var url = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      respond(bundle, HttpStatusCode.OK)
    }

    ok(client(engine).downloadBundle(artifact("https://cdn.test/files/job-1.ktx")))

    assertEquals("https://cdn.test/files/job-1.ktx", url)
  }

  @Test
  fun `the checksum is compared case-insensitively`() = runTest {
    val engine = MockEngine { respond(bundle, HttpStatusCode.OK) }

    val bytes = ok(client(engine).downloadBundle(artifact("/a.ktx", sha = bundleSha.uppercase())))

    assertArrayEquals(bundle, bytes)
  }

  @Test
  fun `a bundle whose checksum does not match is a Failed and the bytes are not handed on`() = runTest {
    val tampered = bundle.copyOf().also { it[10] = (it[10] + 1).toByte() }
    val engine = MockEngine { respond(tampered, HttpStatusCode.OK) }

    val result = client(engine).downloadBundle(artifact("/a.ktx"))

    assertTrue(result.toString(), result is ForgeResult.Failed)
    says(failed(result), "контрольная сумма", "checksum")
  }

  @Test
  fun `a failed download status is a Failed`() = runTest {
    val message = failed(client(answering("", HttpStatusCode.NotFound)).downloadBundle(artifact("/a.ktx")))

    says(message, "не нашла эту задачу", "no longer has this job")
  }
}
