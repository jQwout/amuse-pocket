package com.aiopenpocket.agent.core.llm

// @trace video-na-vhod-v-uroke-muse-spark-08ccdb

import android.util.Base64
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Muse Spark video client against a fake engine. Request and response shapes are WRITTEN FROM Meta's
 * documentation (dev.meta.ai, read on Oct 30, 2026), not recorded from a live answer: no paid call was
 * ever made. Robolectric for `android.util.Base64` and `Log`, as in the neighbouring client tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoUnderstandingClientTest {

  private val clip = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 1, 2, 3)

  private class RecordingUsage : LlmUsageRecorder {
    val calls = mutableListOf<LlmCallUsage>()
    override suspend fun record(usage: LlmCallUsage) {
      calls += usage
    }
  }

  private fun answer(text: String = "A cat crosses the room.", usage: String? = """{"prompt_tokens":1000,"completion_tokens":200,"total_tokens":1200}"""): String {
    val use = if (usage == null) "" else ""","usage":$usage"""
    return """{"id":"c","choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}]$use}"""
  }

  private fun json(body: String) = MockEngine {
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
  }

  private fun client(engine: MockEngine, usage: LlmUsageRecorder = NoOpLlmUsageRecorder, key: () -> String = { "sk-test" }) =
    VideoUnderstandingClient(engine, key, usage)

  @Test
  fun `sends a video_url part and max_completion_tokens to the Meta chat endpoint`() = runTest {
    var url = ""
    var auth: String? = null
    var body = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      body = (request.body as TextContent).text
      respond(answer(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val text = client(engine).describe("What happens?", clip, "video/mp4")

    assertEquals("A cat crosses the room.", text)
    assertEquals("https://api.meta.ai/v1/chat/completions", url)
    assertEquals("Bearer sk-test", auth)
    val sent = Json.parseToJsonElement(body).jsonObject
    // Meta answers 400 to unsupported parameters: the top-level keys are pinned, not just their values.
    assertEquals(setOf("model", "messages", "max_completion_tokens"), sent.keys)
    assertEquals("muse-spark-1.3", sent.getValue("model").jsonPrimitive.content)
    // max_tokens is a deprecated alias; reasoning tokens count against the limit, so there must be headroom.
    assertTrue(sent.getValue("max_completion_tokens").jsonPrimitive.int >= 2000)
    val message = sent.getValue("messages").jsonArray.single().jsonObject
    assertEquals("user", message.getValue("role").jsonPrimitive.content)
    val parts = message.getValue("content").jsonArray.map { it.jsonObject }
    assertEquals(2, parts.size)
    assertEquals("text", parts[0].getValue("type").jsonPrimitive.content)
    assertEquals("What happens?", parts[0].getValue("text").jsonPrimitive.content)
    assertEquals(setOf("type", "video_url"), parts[1].keys)
    assertEquals("video_url", parts[1].getValue("type").jsonPrimitive.content)
    val videoUrl = parts[1].getValue("video_url").jsonObject
    // fps is optional and not sent; the clip goes inline as a data URL.
    assertEquals(setOf("url"), videoUrl.keys)
    assertEquals("data:video/mp4;base64," + Base64.encodeToString(clip, Base64.NO_WRAP), videoUrl.getValue("url").jsonPrimitive.content)
  }

  /** OpenRouter's `usage: {include: true}` is not a Meta parameter. */
  @Test
  fun `does not send the OpenRouter usage include field`() = runTest {
    var body = ""
    val engine = MockEngine { request ->
      body = (request.body as TextContent).text
      respond(answer(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    client(engine).describe("q", clip, "video/mp4")

    assertFalse("usage" in Json.parseToJsonElement(body).jsonObject.keys)
    assertFalse(body.contains("\"include\""))
    assertFalse(body.contains("\"max_tokens\""))
  }

  @Test
  fun `no key means no request and no spend`() = runTest {
    var calls = 0
    val usage = RecordingUsage()
    val engine = MockEngine {
      calls++
      respondError(HttpStatusCode.Unauthorized)
    }
    val c = client(engine, usage, key = { "" })

    assertFalse(c.isConfigured())
    assertNull(c.describe("q", clip, "video/mp4"))
    assertEquals(0, calls)
    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `a key makes the client configured`() {
    assertTrue(client(json(answer())).isConfigured())
  }

  /** Meta takes MP4 only: another type is a certain 400 on a request of megabytes, so it is not sent. */
  @Test
  fun `a clip that is not an mp4 is not sent`() = runTest {
    var calls = 0
    val engine = MockEngine {
      calls++
      respond(answer(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    assertNull(client(engine).describe("q", clip, "video/quicktime"))
    assertEquals(0, calls)
  }

  /** Meta sends tokens, never a price: the client prices them from the Spark list. */
  @Test
  fun `usage is recorded with a cost computed from the Spark price list`() = runTest {
    val usage = RecordingUsage()

    client(json(answer()), usage).describe("q", clip, "video/mp4")

    val call = usage.calls.single()
    assertEquals(LlmProvider.META, call.provider)
    assertEquals("muse-spark-1.3", call.model)
    assertEquals(1000, call.promptTokens)
    assertEquals(200, call.completionTokens)
    // 1000 * 1.25 / 1M + 200 * 4.25 / 1M
    assertEquals(0.0012500 + 0.00085, call.costUsd!!, 1e-9)
    assertFalse(call.estimated)
    assertFalse(call.hadTools)
  }

  @Test
  fun `cached prompt tokens are priced at the cached rate`() = runTest {
    val usage = RecordingUsage()
    val body = answer(
      usage = """{"prompt_tokens":1000,"completion_tokens":200,"prompt_tokens_details":{"cached_tokens":400},"completion_tokens_details":{"reasoning_tokens":150}}""",
    )

    client(json(body), usage).describe("q", clip, "video/mp4")

    // 600 fresh * 1.25 + 400 cached * 0.15 + 200 out * 4.25, per 1M
    assertEquals((600 * 1.25 + 400 * 0.15 + 200 * 4.25) / 1_000_000.0, usage.calls.single().costUsd!!, 1e-9)
  }

  @Test
  fun `an answer without usage is recorded as an estimate with no price`() = runTest {
    val usage = RecordingUsage()

    client(json(answer(usage = null)), usage).describe("q", clip, "video/mp4")

    val call = usage.calls.single()
    assertNull(call.costUsd)
    assertTrue(call.estimated)
    assertEquals(0, call.promptTokens)
  }

  @Test
  fun `a 500 is no answer and is not retried`() = runTest {
    var calls = 0
    val usage = RecordingUsage()
    val engine = MockEngine {
      calls++
      respondError(HttpStatusCode.InternalServerError)
    }

    assertNull(client(engine, usage).describe("q", clip, "video/mp4"))
    assertEquals(1, calls)
    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `429 is retried and the retry carries the same body`() = runTest {
    val bodies = mutableListOf<String>()
    val usage = RecordingUsage()
    val engine = MockEngine { request ->
      bodies += (request.body as TextContent).text
      if (bodies.size == 1) {
        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "1"))
      } else {
        respond(answer(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
      }
    }

    assertEquals("A cat crosses the room.", client(engine, usage).describe("q", clip, "video/mp4"))
    assertEquals(2, bodies.size)
    assertEquals(bodies[0], bodies[1])
    assertEquals(1, usage.calls.size)
  }

  @Test
  fun `503 gives up after the attempts`() = runTest {
    var calls = 0
    val engine = MockEngine {
      calls++
      respondError(HttpStatusCode.ServiceUnavailable)
    }

    assertNull(client(engine).describe("q", clip, "video/mp4"))
    assertEquals(RetryBackoff.MAX_ATTEMPTS, calls)
  }

  @Test
  fun `an empty answer is no answer and records nothing`() = runTest {
    val usage = RecordingUsage()

    assertNull(client(json(answer(text = "  ")), usage).describe("q", clip, "video/mp4"))
    assertNull(client(json("""{"choices":[]}"""), usage).describe("q", clip, "video/mp4"))
    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `a body that is not JSON is no answer, not an exception`() = runTest {
    assertNull(client(json("not json at all")).describe("q", clip, "video/mp4"))
  }
}
