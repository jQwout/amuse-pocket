package com.aiopenpocket.agent.core.llm

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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
import org.robolectric.shadows.ShadowLog
import kotlin.random.Random

/**
 * The SAM client against a fake Ktor engine. The request body and the answers are WRITTEN FROM Meta's documentation
 * (dev.meta.ai/docs/sam/segmenting and the cookbook), not recorded from a live response: no call was ever made, the
 * owner's budget is zero, and a SAM call costs money and needs billing set up at Meta. So these tests pin what the
 * client does with the documented shapes; whether Meta's real answers match them is not established here.
 *
 * Robolectric — because of `android.util.Base64` and `Log` inside the client, as in the neighbouring client tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SegmentationClientTest {

  private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

  private val record =
    "<0f>0<|box;x1=181;y1=365;x2=556;y2=486;w=1516;h=1600;c=0.96|><|mask;x=0;y=0;c=0.96;data=122,376,!!!!!ab|>"

  private class RecordingUsage : LlmUsageRecorder {
    val calls = mutableListOf<LlmCallUsage>()
    override suspend fun record(usage: LlmCallUsage) {
      calls += usage
    }
  }

  /** One `data:` line of an SSE stream. */
  private fun data(json: String) = "data: $json\n\n"

  private fun delta(text: String) =
    data("""{"type":"response.output_text.delta","item_id":"m","output_index":0,"content_index":0,"delta":${JsonPrimitive(text)}}""")

  private fun partDone(text: String) =
    data("""{"type":"response.content_part.done","item_id":"m","output_index":0,"content_index":0,"part":{"type":"output_text","text":${JsonPrimitive(text)}}}""")

  private fun completed(usage: String? = """{"images_processed":1}"""): String {
    val withUsage = if (usage == null) "" else ""","usage":$usage"""
    return data("""{"type":"response.completed","response":{"id":"r","status":"completed"$withUsage}}""")
  }

  /** The documented shape: deltas, then content_part.done — and NO response.output_text.done, as Meta's cookbook warns. */
  private fun streamOf(text: String, usage: String? = """{"images_processed":1}""") =
    delta(text.take(20)) + delta(text.drop(20)) + partDone(text) + completed(usage)

  private fun MockRequestHandleScope.sse(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))

  private fun client(
    usage: LlmUsageRecorder = NoOpLlmUsageRecorder,
    key: String = "meta-key",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ) = SegmentationClient(MockEngine(handler), { key }, usage, random = Random(1))

  @Test
  fun `sends a Responses API request with the key, the concept and the picture`() = runTest {
    var url = ""
    var auth: String? = null
    var accept: String? = null
    var body = ""
    val sam = client { request ->
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      accept = request.headers[HttpHeaders.Accept]
      body = (request.body as TextContent).text
      sse(streamOf(record))
    }

    sam.segment("cup", jpeg, "image/jpeg")

    assertEquals("https://api.meta.ai/v1/responses", url)
    assertEquals("Bearer meta-key", auth)
    assertEquals("text/event-stream", accept)
    val sent = Json.parseToJsonElement(body).jsonObject
    // With a DOT: "sam-3-1" on Meta's marketing page is a typo that the API does not know.
    assertEquals("sam-3.1", sent["model"]!!.jsonPrimitive.content)
    assertTrue(sent["stream"]!!.jsonPrimitive.boolean)
    // store:false — the learner's photo must not stay on Meta's side (as documented; not verified with a live call).
    assertFalse(sent["store"]!!.jsonPrimitive.boolean)
    val metadata = sent["metadata"]!!.jsonObject
    assertEquals("one_bit", metadata["mask_encoding"]!!.jsonPrimitive.content)
    assertEquals("true", metadata["include_confidence"]!!.jsonPrimitive.content)
    val message = sent["input"]!!.jsonArray.single().jsonObject
    assertEquals("message", message["type"]!!.jsonPrimitive.content)
    assertEquals("user", message["role"]!!.jsonPrimitive.content)
    val parts = message["content"]!!.jsonArray
    assertEquals(2, parts.size)
    assertEquals("input_text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
    assertEquals("cup", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
    assertEquals("input_image", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
    val expectedUrl = "data:image/jpeg;base64," + android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
    assertEquals(expectedUrl, parts[1].jsonObject["image_url"]!!.jsonPrimitive.content)
    // Nothing else goes out: Meta answers 400 to what it does not know.
    assertEquals(setOf("model", "input", "stream", "store", "metadata"), sent.keys)
  }

  @Test
  fun `deltas split mid-token are glued into one answer`() = runTest {
    val sam = client { sse(streamOf(record)) }

    val result = sam.segment("cup", jpeg, "image/jpeg") as SegmentationResult.Success

    val found = result.records.single()
    assertEquals("0", found.id)
    assertEquals(SegmentBox(181, 365, 556, 486, 1516, 1600, 0.96), found.box)
    assertEquals("cup", result.concept)
    assertTrue(result.diagnostics.isEmpty())
  }

  /** The API sends content_part.done but not output_text.done; a stream with only the former must still work. */
  @Test
  fun `a stream with no output_text done event completes`() = runTest {
    val body = delta(record) + partDone(record) + completed()
    assertFalse("the fixture must not carry output_text.done", body.contains("response.output_text.done"))

    val result = client { sse(body) }.segment("cup", jpeg, "image/jpeg")

    assertEquals(1, (result as SegmentationResult.Success).records.size)
  }

  @Test
  fun `the part text stands in when no delta came`() = runTest {
    val result = client { sse(partDone(record) + completed()) }.segment("cup", jpeg, "image/jpeg")

    assertEquals(1, (result as SegmentationResult.Success).records.size)
  }

  /** Zero matches: an empty output_text, no deltas at all. A normal answer, billed like any other. */
  @Test
  fun `zero matches is a success with no records and is billed`() = runTest {
    val usage = RecordingUsage()
    val sam = client(usage) { sse(partDone("") + completed()) }

    val result = sam.segment("unicorn", jpeg, "image/jpeg")

    assertEquals(SegmentationResult.Success("unicorn", emptyList(), emptyList()), result)
    assertEquals(1, usage.calls.size)
  }

  @Test
  fun `spend is per picture, from images_processed`() = runTest {
    val usage = RecordingUsage()
    client(usage) { sse(streamOf(record, usage = """{"images_processed":2}""")) }.segment("cup", jpeg, "image/jpeg")

    val call = usage.calls.single()
    assertEquals(LlmProvider.META, call.provider)
    assertEquals("sam-3.1", call.model)
    assertEquals(0, call.promptTokens)
    assertEquals(0, call.completionTokens)
    assertEquals(0.005, call.costUsd!!, 1e-12)
    assertFalse(call.estimated)
    assertFalse(call.hadTools)
  }

  /** No usage in the answer: one call counts as one picture, and the row says it is an estimate. */
  @Test
  fun `without usage one call is one picture and is marked estimated`() = runTest {
    val usage = RecordingUsage()
    client(usage) { sse(streamOf(record, usage = null)) }.segment("cup", jpeg, "image/jpeg")

    val call = usage.calls.single()
    assertEquals(0.0025, call.costUsd!!, 1e-12)
    assertTrue(call.estimated)
  }

  @Test
  fun `no key means no request`() = runTest {
    var calls = 0
    val result = client(key = "") { calls++; sse(streamOf(record)) }.segment("cup", jpeg, "image/jpeg")

    assertEquals(SegmentationResult.Failed(SegmentationFailure.NO_KEY), result)
    assertEquals(0, calls)
  }

  /** Lists and commands do not work with SAM; they are caught here instead of costing a call. */
  @Test
  fun `a concept that is not one noun phrase is never sent`() = runTest {
    var calls = 0
    val sam = client { calls++; sse(streamOf(record)) }

    // «чашка» is the first thing a learner on the Russian screen types: it must not cost a call.
    for (bad in listOf("", "   ", "cup, plate", "cup;plate", "a\nb", "<|box|>", "x".repeat(65), "чашка", "cup!", "café", "杯子", "cup.")) {
      val result = sam.segment(bad, jpeg, "image/jpeg")
      assertEquals("'$bad'", SegmentationFailure.BAD_CONCEPT, (result as SegmentationResult.Failed).reason)
    }
    assertEquals(0, calls)
  }

  @Test
  fun `the concept is sent trimmed and with single spaces`() = runTest {
    var body = ""
    client { request -> body = (request.body as TextContent).text; sse(streamOf(record)) }
      .segment("  yellow   school bus ", jpeg, "image/jpeg")

    val text = Json.parseToJsonElement(body).jsonObject["input"]!!.jsonArray.single().jsonObject["content"]!!
      .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
    assertEquals("yellow school bus", text)
  }

  @Test
  fun `a picture over the schema limit is never sent`() = runTest {
    var calls = 0
    // base64 grows by a third: 15.8 million bytes is about 21.07 million characters, over the 20 971 520 limit.
    val huge = ByteArray(15_800_000)

    val result = client { calls++; sse(streamOf(record)) }.segment("cup", huge, "image/jpeg")

    assertEquals(SegmentationFailure.IMAGE_TOO_LARGE, (result as SegmentationResult.Failed).reason)
    assertEquals(0, calls)
  }

  @Test
  fun `each failing status maps to its own reason and a 400 is not retried`() = runTest {
    val expected = mapOf(
      400 to SegmentationFailure.BAD_REQUEST,
      401 to SegmentationFailure.UNAUTHORIZED,
      402 to SegmentationFailure.BILLING,
      403 to SegmentationFailure.UNAUTHORIZED,
      404 to SegmentationFailure.NO_MODEL_ACCESS,
      413 to SegmentationFailure.IMAGE_TOO_LARGE,
    )
    for ((status, reason) in expected) {
      val calls = AtomicInteger()
      val usage = RecordingUsage()
      val sam = client(usage) {
        calls.incrementAndGet()
        respond("""{"error":{"code":"x"}}""", HttpStatusCode.fromValue(status))
      }

      val result = sam.segment("cup", jpeg, "image/jpeg")

      assertEquals("HTTP $status", reason, (result as SegmentationResult.Failed).reason)
      assertEquals("HTTP $status must not be retried", 1, calls.get())
      assertTrue("a failed call records no spend", usage.calls.isEmpty())
    }
  }

  @Test
  fun `a 429 waits and then succeeds`() = runTest {
    val calls = AtomicInteger()
    val usage = RecordingUsage()
    val sam = client(usage) {
      if (calls.incrementAndGet() == 1) {
        respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "2"))
      } else {
        sse(streamOf(record))
      }
    }

    val result = sam.segment("cup", jpeg, "image/jpeg")

    assertEquals(1, (result as SegmentationResult.Success).records.size)
    assertEquals(2, calls.get())
    // One call billed, not two: the 429 never reached the model.
    assertEquals(1, usage.calls.size)
  }

  @Test
  fun `server errors are retried and then reported`() = runTest {
    for (status in listOf(500, 503, 504)) {
      val calls = AtomicInteger()
      val usage = RecordingUsage()
      val sam = client(usage) { calls.incrementAndGet(); respond("", HttpStatusCode.fromValue(status)) }

      val result = sam.segment("cup", jpeg, "image/jpeg")

      assertEquals("HTTP $status", SegmentationFailure.SERVER, (result as SegmentationResult.Failed).reason)
      assertEquals("HTTP $status", SegmentationClient.MAX_ATTEMPTS, calls.get())
      // Meta rejected every try: nothing was processed, nothing is billed.
      assertTrue("HTTP $status", usage.calls.isEmpty())
    }
  }

  @Test
  fun `a 429 that never clears is reported as rate limited`() = runTest {
    val calls = AtomicInteger()
    val sam = client { calls.incrementAndGet(); respond("", HttpStatusCode.TooManyRequests) }

    val result = sam.segment("cup", jpeg, "image/jpeg")

    assertEquals(SegmentationFailure.RATE_LIMITED, (result as SegmentationResult.Failed).reason)
    assertEquals(SegmentationClient.MAX_ATTEMPTS, calls.get())
  }

  /** "Come back in a minute" is no use to someone standing on a lesson step. */
  @Test
  fun `a Retry-After that is too long is not waited out`() = runTest {
    val calls = AtomicInteger()
    val sam = client { calls.incrementAndGet(); respond("", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "120")) }

    val result = sam.segment("cup", jpeg, "image/jpeg")

    assertEquals(SegmentationFailure.RATE_LIMITED, (result as SegmentationResult.Failed).reason)
    assertEquals(1, calls.get())
  }

  @Test
  fun `retry delay follows Retry-After and the backoff, with bounded jitter`() {
    val random = Random(7)
    repeat(50) {
      val plain = SegmentationClient.retryDelayMs(1, null, random)
      assertTrue("first backoff is 1 s plus jitter, was $plain", plain in 1_000L..1_500L)
      val second = SegmentationClient.retryDelayMs(2, null, random)
      assertTrue("second backoff is 2 s plus jitter, was $second", second in 2_000L..2_500L)
      val asked = SegmentationClient.retryDelayMs(1, "5", random)
      assertTrue("Retry-After 5 s wins over the 1 s backoff, was $asked", asked in 5_000L..5_500L)
      // A header shorter than the backoff does not shorten it.
      val short = SegmentationClient.retryDelayMs(2, "1", random)
      assertTrue("was $short", short in 2_000L..2_500L)
      // An HTTP-date is not parsed: the plain backoff stands in.
      val date = SegmentationClient.retryDelayMs(1, "Wed, 21 Oct 2026 07:28:00 GMT", random)
      assertTrue("was $date", date in 1_000L..1_500L)
    }
  }

  /**
   * Without response.completed, an empty lane would be a guess dressed up as "found nothing". But the HTTP 200 came:
   * Meta took the picture, so the call is on the learner's bill and must be on the spend card — as an estimate.
   */
  @Test
  fun `a stream that ends without completed is incomplete, not nothing found, and is still billed`() = runTest {
    val usage = RecordingUsage()
    val sam = client(usage) { sse(delta(record)) }

    val result = sam.segment("cup", jpeg, "image/jpeg")

    assertEquals(SegmentationFailure.INCOMPLETE, (result as SegmentationResult.Failed).reason)
    val call = usage.calls.single()
    assertEquals(0.0025, call.costUsd!!, 1e-12)
    assertTrue("the picture count never arrived, so the row is an estimate", call.estimated)
    assertEquals(LlmProvider.META, call.provider)
  }

  @Test
  fun `an error event or a refusal in the stream is a failure`() = runTest {
    val error = data("""{"type":"error","error":{"message":"content policy"}}""")
    val refusal = data("""{"type":"response.refusal.delta","delta":"no"}""") + completed()
    val failed = data("""{"type":"response.failed","response":{"error":{"message":"boom"}}}""")
    for (body in listOf(error, refusal, failed)) {
      val result = client { sse(body) }.segment("cup", jpeg, "image/jpeg")
      assertEquals(body, SegmentationFailure.REFUSED, (result as SegmentationResult.Failed).reason)
    }
  }

  /**
   * Text with no record in it is not the documented empty answer: it is something we cannot read. The call was
   * processed all the same — the stream even said how many pictures — so it is billed and recorded, as measured.
   */
  @Test
  fun `prose without any record is a bad response, not nothing found, and is still billed`() = runTest {
    val usage = RecordingUsage()
    val result = client(usage) { sse(delta("I cannot do that.") + completed()) }.segment("cup", jpeg, "image/jpeg")

    assertEquals(SegmentationFailure.BAD_RESPONSE, (result as SegmentationResult.Failed).reason)
    val call = usage.calls.single()
    assertEquals(0.0025, call.costUsd!!, 1e-12)
    assertFalse("images_processed came in the stream: this one is measured", call.estimated)
  }

  /** The model's prose can describe a private photo: logcat and the result get the length, never the text. */
  @Test
  fun `the model's prose is not logged and not carried in the failure`() = runTest {
    ShadowLog.clear()
    val prose = "A woman in a red coat holds a child on a beach at dusk."

    val result = client { sse(delta(prose) + completed()) }.segment("cup", jpeg, "image/jpeg") as SegmentationResult.Failed

    assertFalse(result.detail.orEmpty().contains("woman"))
    val logged = ShadowLog.getLogs().joinToString("\n") { it.msg.orEmpty() }
    assertFalse("prose leaked into logcat: $logged", logged.contains("woman") || logged.contains("beach"))
    assertTrue("the length is logged instead: $logged", logged.contains("${prose.length} chars"))
  }

  /** A connection that breaks while the answer is read: Meta had the picture, so the call is recorded (as an estimate). */
  @Test
  fun `a connection that breaks after the 200 is a network failure that is still billed`() = runTest {
    val usage = RecordingUsage()
    val sam = client(usage) {
      val channel = ByteChannel(autoFlush = true)
      channel.writeStringUtf8(delta(record.take(20)))
      channel.close(java.io.IOException("connection reset"))
      respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    val result = sam.segment("cup", jpeg, "image/jpeg")

    assertEquals(SegmentationFailure.NETWORK, (result as SegmentationResult.Failed).reason)
    val call = usage.calls.single()
    assertEquals(0.0025, call.costUsd!!, 1e-12)
    assertTrue(call.estimated)
  }

  /** The answer was whole before the line dropped: nothing is lost, and the call is billed as measured. */
  @Test
  fun `a drop after response completed does not spoil a finished answer`() = runTest {
    val usage = RecordingUsage()
    val sam = client(usage) {
      val channel = ByteChannel(autoFlush = true)
      channel.writeStringUtf8(streamOf(record))
      // The line drops only after the client has read everything that was sent.
      CoroutineScope(Dispatchers.Default).launch {
        while (channel.availableForRead > 0) delay(10)
        delay(100)
        channel.close(java.io.IOException("connection reset"))
      }
      respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }

    val result = sam.segment("cup", jpeg, "image/jpeg")

    assertEquals(1, (result as SegmentationResult.Success).records.size)
    assertFalse(usage.calls.single().estimated)
  }

  /** A cancel (the learner leaves the screen) after the 200 does not un-bill the picture either. */
  @Test
  fun `a cancel after the 200 still records the call`() = runTest {
    val usage = RecordingUsage()
    val channel = ByteChannel(autoFlush = true)
    val reached = CompletableDeferred<Unit>()
    val sam = client(usage) {
      channel.writeStringUtf8(delta(record.take(20)))
      reached.complete(Unit)
      respond(channel, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    var result: SegmentationResult? = null
    val job = launch { result = sam.segment("cup", jpeg, "image/jpeg") }

    reached.await()
    // Real time on a real dispatcher: wait until the client has read what was written and sits waiting for more.
    withContext(Dispatchers.Default) {
      while (channel.availableForRead > 0) delay(10)
      delay(100)
    }
    job.cancelAndJoin()

    assertNull(result)
    assertEquals(1, usage.calls.size)
    assertTrue(usage.calls.single().estimated)
  }

  /** Meta said the work did not complete: an error event is not a billed picture (a judgment about the schema, see the KDoc). */
  @Test
  fun `an error event in the stream is not recorded`() = runTest {
    val usage = RecordingUsage()

    client(usage) { sse(data("""{"type":"error","error":{"message":"content policy"}}""")) }.segment("cup", jpeg, "image/jpeg")

    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `an unparseable chunk and keep-alive lines do not break the stream`() = runTest {
    val body = ": keep-alive\n\n" + "event: response.output_text.delta\n" + delta(record) + "data: {not json\n\n" + completed() + "data: [DONE]\n\n"

    val result = client { sse(body) }.segment("cup", jpeg, "image/jpeg")

    assertEquals(1, (result as SegmentationResult.Success).records.size)
  }

  @Test
  fun `a malformed line is reported but the good records stay`() = runTest {
    val text = "$record\n<0f>\n"

    val result = client { sse(streamOf(text)) }.segment("cup", jpeg, "image/jpeg") as SegmentationResult.Success

    assertEquals(1, result.records.size)
    assertEquals(listOf("malformed_record"), result.diagnostics.map { it.code })
  }

  @Test
  fun `a transport error is a failure, not an exception`() = runTest {
    val result = client { throw java.io.IOException("no route to host") }.segment("cup", jpeg, "image/jpeg")

    assertEquals(SegmentationFailure.NETWORK, (result as SegmentationResult.Failed).reason)
  }

  /** The client never throws — except cancellation, which must pass through or Stop would not stop it. */
  @Test
  fun `cancellation is not swallowed`() = runTest {
    val started = CompletableDeferred<Unit>()
    val sam = client {
      started.complete(Unit)
      awaitCancellation()
    }
    var result: SegmentationResult? = null
    val job = launch { result = sam.segment("cup", jpeg, "image/jpeg") }

    started.await()
    job.cancelAndJoin()

    // Turned into a Failed, the call would "return" after the cancel: the assignment above would have run.
    assertNull(result)
    assertTrue(job.isCancelled)
  }

  @Test
  fun `isConfigured follows the key`() {
    assertTrue(client { sse("") }.isConfigured())
    assertFalse(client(key = " ") { sse("") }.isConfigured())
  }

  @Test
  fun `concept check names the problem`() {
    assertNull(SegmentationConcept.problemOf("yellow school bus"))
    assertEquals(SegmentationConcept.Problem.EMPTY, SegmentationConcept.problemOf("  "))
    assertEquals(SegmentationConcept.Problem.LIST, SegmentationConcept.problemOf("cup, plate"))
    assertEquals(SegmentationConcept.Problem.RESERVED_CHARACTERS, SegmentationConcept.problemOf("<|point|>"))
    assertEquals(SegmentationConcept.Problem.TOO_LONG, SegmentationConcept.problemOf("a".repeat(65)))
    assertNull(SegmentationConcept.normalize("cup, plate"))
    assertEquals("cup", SegmentationConcept.normalize(" cup "))
  }

  /** Latin letters, digits, space, hyphen and apostrophe are an English noun phrase; anything else is not. */
  @Test
  fun `only an English-looking phrase passes`() {
    assertEquals(SegmentationConcept.Problem.NOT_ENGLISH, SegmentationConcept.problemOf("чашка"))
    assertEquals(SegmentationConcept.Problem.NOT_ENGLISH, SegmentationConcept.problemOf("cup чашка"))
    assertEquals(SegmentationConcept.Problem.NOT_ENGLISH, SegmentationConcept.problemOf("café"))
    assertEquals(SegmentationConcept.Problem.NOT_ENGLISH, SegmentationConcept.problemOf("cup!"))
    for (fine in listOf("cup", "yellow school bus", "T-shirt", "person's hand", "tv 2", "Laptop")) {
      assertNull("'$fine' is English", SegmentationConcept.problemOf(fine))
    }
    // A phone keyboard types the typographic apostrophe; it goes out as the plain one.
    assertEquals("person's hand", SegmentationConcept.normalize("person\u2019s hand"))
  }
}
