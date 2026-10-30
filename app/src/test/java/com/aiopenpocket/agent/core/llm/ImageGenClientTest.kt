package com.aiopenpocket.agent.core.llm

// @trace kartinki-urokov-cherez-muse-image-fd6f8c

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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Muse Image client against a fake engine: what goes to Meta's `/v1/images/generations` and what
 * counts as an image. The response shapes are WRITTEN FROM Meta's documentation (dev.meta.ai, read on
 * Oct 30, 2026), not recorded from a live answer - no paid call was ever made.
 *
 * The main thing here is that the client never throws and never sends anything extra: an image in a
 * lesson is optional, and an unknown top-level field is a 400 at Meta instead of an image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageGenClientTest {

  private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
  private val b64 = Base64.encodeToString(png, Base64.NO_WRAP)

  private class RecordingUsage : LlmUsageRecorder {
    val calls = mutableListOf<LlmCallUsage>()
    override suspend fun record(usage: LlmCallUsage) {
      calls += usage
    }
  }

  private fun json(body: String) = MockEngine {
    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
  }

  /** Meta's documented success body: no media type, only `output_format`. */
  private fun ok(format: String? = "webp", usage: String? = """{"input_tokens":12,"output_tokens":4175,"total_tokens":4187}"""): String {
    val fmt = if (format == null) "" else ""","output_format":"$format""""
    val use = if (usage == null) "" else ""","usage":$usage"""
    return """{"created":1,"data":[{"b64_json":"$b64","revised_prompt":"a red panda"}]$fmt,"background":"opaque"$use}"""
  }

  @Test
  fun `sends exactly the documented fields with the key and decodes the image`() = runTest {
    var url = ""
    var auth: String? = null
    var body = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      body = (request.body as TextContent).text
      respond(ok(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    val image = ImageGenClient(engine, { "sk-test" }, NoOpLlmUsageRecorder).generate("a red panda")

    assertArrayEquals(png, image?.bytes)
    assertEquals("https://api.meta.ai/v1/images/generations", url)
    assertEquals(ImageGenClient.META_IMAGES_URL, url)
    assertEquals("Bearer sk-test", auth)
    // Meta answers 400 to an unknown top-level field, so the set of keys is pinned, not just their values.
    val sent = Json.parseToJsonElement(body).jsonObject
    assertEquals(setOf("model", "prompt", "size", "response_format", "output_format"), sent.keys)
    assertEquals("muse-image-1.0", sent.getValue("model").jsonPrimitive.content)
    assertEquals("a red panda", sent.getValue("prompt").jsonPrimitive.content)
    assertEquals("1280x720", sent.getValue("size").jsonPrimitive.content)
    assertEquals("b64_json", sent.getValue("response_format").jsonPrimitive.content)
    assertEquals("webp", sent.getValue("output_format").jsonPrimitive.content)
    // moderation is a 400 on this model.
    assertFalse("moderation" in sent.keys)
  }

  /** The response has no media_type: it is "image/" + the response's own output_format. */
  @Test
  fun `the media type comes from output_format in the response`() = runTest {
    assertEquals("image/webp", ImageGenClient(json(ok("webp")), { "k" }, NoOpLlmUsageRecorder).generate("p")?.mediaType)
    assertEquals("image/png", ImageGenClient(json(ok("png")), { "k" }, NoOpLlmUsageRecorder).generate("p")?.mediaType)
    assertEquals("image/jpeg", ImageGenClient(json(ok("jpeg")), { "k" }, NoOpLlmUsageRecorder).generate("p")?.mediaType)
  }

  @Test
  fun `a response without output_format falls back to the format that was requested`() = runTest {
    assertEquals("image/webp", ImageGenClient(json(ok(format = null)), { "k" }, NoOpLlmUsageRecorder).generate("p")?.mediaType)
  }

  /** Without a key the request is guaranteed to get a 401 — it must not be sent at all. */
  @Test
  fun `no key means no request and no spend`() = runTest {
    var calls = 0
    val usage = RecordingUsage()
    val engine = MockEngine {
      calls++
      respondError(HttpStatusCode.Unauthorized)
    }
    val client = ImageGenClient(engine, { "  " }, usage)

    assertFalse(client.isConfigured())
    assertNull(client.generate("a red panda"))
    assertEquals(0, calls)
    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `a key makes the client configured`() {
    assertTrue(ImageGenClient(json(ok()), { "sk" }, NoOpLlmUsageRecorder).isConfigured())
  }

  /** The key is read on every request, so one typed in Settings works at once. */
  @Test
  fun `the key is read on every request`() = runTest {
    var key = ""
    var calls = 0
    val engine = MockEngine {
      calls++
      respond(ok(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val client = ImageGenClient(engine, { key }, NoOpLlmUsageRecorder)

    assertNull(client.generate("p"))
    key = "sk-new"
    assertTrue(client.generate("p") != null)
    assertEquals(1, calls)
  }

  @Test
  fun `a 500 is no image and is not retried`() = runTest {
    var calls = 0
    val engine = MockEngine {
      calls++
      respondError(HttpStatusCode.InternalServerError)
    }

    assertNull(ImageGenClient(engine, { "k" }, NoOpLlmUsageRecorder).generate("a red panda"))
    assertEquals("Meta says a 500 is not worth repeating", 1, calls)
  }

  @Test
  fun `a content policy refusal is no image and is not retried`() = runTest {
    var calls = 0
    val usage = RecordingUsage()
    val engine = MockEngine {
      calls++
      respond(
        """{"error":{"message":"blocked","type":"invalid_request_error","param":null,"code":"content_policy_violation"}}""",
        HttpStatusCode.BadRequest,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }

    assertNull(ImageGenClient(engine, { "k" }, usage).generate("p"))
    assertEquals(1, calls)
    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `429 is retried after Retry-After and then succeeds`() = runTest {
    var calls = 0
    val usage = RecordingUsage()
    val engine = MockEngine {
      calls++
      if (calls == 1) {
        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
      } else {
        respond(ok(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
      }
    }

    val image = ImageGenClient(engine, { "k" }, usage).generate("p")

    assertArrayEquals(png, image?.bytes)
    assertEquals(2, calls)
    // Only the image that came back is billed, not the refused attempt.
    assertEquals(1, usage.calls.size)
  }

  @Test
  fun `503 is retried and gives up after the attempts`() = runTest {
    var calls = 0
    val usage = RecordingUsage()
    val engine = MockEngine {
      calls++
      respondError(HttpStatusCode.ServiceUnavailable)
    }

    assertNull(ImageGenClient(engine, { "k" }, usage).generate("p"))
    assertEquals(RetryBackoff.MAX_ATTEMPTS, calls)
    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `a response without image data is no image`() = runTest {
    assertNull(ImageGenClient(json("""{"data":[]}"""), { "k" }, NoOpLlmUsageRecorder).generate("p"))
    assertNull(ImageGenClient(json("""{"data":[{"b64_json":""}]}"""), { "k" }, NoOpLlmUsageRecorder).generate("p"))
    assertNull(ImageGenClient(json("not json at all"), { "k" }, NoOpLlmUsageRecorder).generate("p"))
  }

  /** An image is a paid call: it must land in the same spending card as the chat turns, at $0.01. */
  @Test
  fun `a generated image is recorded at the flat price with the usage tokens`() = runTest {
    val usage = RecordingUsage()

    ImageGenClient(json(ok()), { "k" }, usage).generate("p")

    val call = usage.calls.single()
    assertEquals(LlmProvider.META, call.provider)
    assertEquals("muse-image-1.0", call.model)
    assertEquals(12, call.promptTokens)
    assertEquals(4175, call.completionTokens)
    assertEquals(0.01, call.costUsd!!, 1e-9)
    // A flat list price is known, not guessed.
    assertFalse(call.estimated)
    assertFalse(call.hadTools)
  }

  @Test
  fun `the price does not depend on the answer carrying a usage block`() = runTest {
    val usage = RecordingUsage()

    ImageGenClient(json(ok(usage = null)), { "k" }, usage).generate("p")

    val call = usage.calls.single()
    assertEquals(0.01, call.costUsd!!, 1e-9)
    assertEquals(0, call.promptTokens)
    assertEquals(0, call.completionTokens)
    assertFalse(call.estimated)
  }

  @Test
  fun `a failed generation records nothing`() = runTest {
    val usage = RecordingUsage()

    ImageGenClient(MockEngine { respondError(HttpStatusCode.BadGateway) }, { "k" }, usage).generate("p")

    assertTrue(usage.calls.isEmpty())
  }
}
