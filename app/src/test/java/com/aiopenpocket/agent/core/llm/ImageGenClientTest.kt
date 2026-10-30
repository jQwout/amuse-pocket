package com.aiopenpocket.agent.core.llm

import android.util.Base64
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
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
 * The Muse Image client: what goes to the provider and what counts as an image.
 *
 * The main thing here is that the client never throws and never sends anything extra: an image in a
 * lesson is optional, and an unknown parameter turns it into a 400 at a strict provider.
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

  @Test
  fun `sends only model and prompt with the key and decodes the image`() = runTest {
    var url = ""
    var auth: String? = null
    var body = ""
    val engine = MockEngine { request ->
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      body = (request.body as TextContent).text
      respond(
        """{"data":[{"b64_json":"$b64","media_type":"image/png"}]}""",
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }

    val image = ImageGenClient(engine, { "sk-test" }, NoOpLlmUsageRecorder).generate("a red panda")

    assertArrayEquals(png, image?.bytes)
    assertEquals("image/png", image?.mediaType)
    assertEquals(ImageGenClient.META_IMAGES_URL, url)
    assertEquals("Bearer sk-test", auth)
    // Exactly two fields: nothing but the model and the prompt is sent.
    assertEquals("""{"model":"muse-image-1.0","prompt":"a red panda"}""", body)
  }

  /** Without a key the request is guaranteed to get a 401 — it must not be sent at all. */
  @Test
  fun `no key means no request`() = runTest {
    var calls = 0
    val engine = MockEngine {
      calls++
      respondError(HttpStatusCode.Unauthorized)
    }

    assertNull(ImageGenClient(engine, { "" }, NoOpLlmUsageRecorder).generate("a red panda"))
    assertEquals(0, calls)
  }

  @Test
  fun `an HTTP error is no image, not an exception`() = runTest {
    val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

    assertNull(ImageGenClient(engine, { "k" }, NoOpLlmUsageRecorder).generate("a red panda"))
  }

  @Test
  fun `a response without image data is no image`() = runTest {
    assertNull(ImageGenClient(json("""{"data":[]}"""), { "k" }, NoOpLlmUsageRecorder).generate("p"))
    assertNull(ImageGenClient(json("""{"data":[{"b64_json":""}]}"""), { "k" }, NoOpLlmUsageRecorder).generate("p"))
    assertNull(ImageGenClient(json("""не json"""), { "k" }, NoOpLlmUsageRecorder).generate("p"))
  }

  /** An image is a paid call: it must land in the same spending card as the chat turns. */
  @Test
  fun `a generated image is recorded with the provider's cost`() = runTest {
    val usage = RecordingUsage()
    val engine = json(
      """{"data":[{"b64_json":"$b64"}],"usage":{"prompt_tokens":0,"completion_tokens":4175,"cost":0.01}}""",
    )

    ImageGenClient(engine, { "k" }, usage).generate("p")

    val call = usage.calls.single()
    assertEquals(LlmProvider.META, call.provider)
    assertEquals("muse-image-1.0", call.model)
    assertEquals(4175, call.completionTokens)
    assertEquals(0.01, call.costUsd!!, 1e-9)
    assertFalse(call.estimated)
    assertFalse(call.hadTools)
  }

  @Test
  fun `a failed generation records nothing`() = runTest {
    val usage = RecordingUsage()

    ImageGenClient(MockEngine { respondError(HttpStatusCode.BadGateway) }, { "k" }, usage).generate("p")

    assertTrue(usage.calls.isEmpty())
  }
}
