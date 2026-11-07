package com.aiopenpocket.agent.core.forge

// @trace screen-brief-and-mockup-before-the-forge-builds-82cfb2

import android.util.Base64
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.LlmCallUsage
import com.aiopenpocket.agent.core.llm.LlmUsageRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The mockup generator against a fake Meta: which sizes it asks for, when it falls back, and that a
 * drawing the person walked away from is still paid for in the spend card but leaves no file behind.
 * Error bodies follow Meta's documented `{"error":{"message","type","param","code"}}` - not a live answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ScreenMockupGeneratorTest {

  private val app = RuntimeEnvironment.getApplication()
  private val dir get() = File(app.filesDir, "forge_mockups")
  private val brief = ScreenBrief("Glasses today", setOf(ScreenBlock.TOTAL), ScreenLayout.DASHBOARD)
  private val b64 = Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)
  private val ok = """{"data":[{"b64_json":"$b64"}],"output_format":"webp"}"""

  private val sizes = CopyOnWriteArrayList<String>()
  private val usage = CopyOnWriteArrayList<LlmCallUsage>()

  @Before
  fun setUp() {
    dir.deleteRecursively()
  }

  private fun generator(handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
    ScreenMockupGenerator(
      app,
      ImageGenClient(
        MockEngine { request ->
          val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
          sizes += body.getValue("size").jsonPrimitive.content
          handler(request)
        },
        { "meta-key" },
        object : LlmUsageRecorder {
          override suspend fun record(usage: LlmCallUsage) {
            this@ScreenMockupGeneratorTest.usage += usage
          }
        },
      ),
    )

  private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

  @Test
  fun `a portrait that Meta draws is the only request`() = runTest {
    val file = generator { json(ok) }.draw("agent-1", "Water", brief, dark = true)

    assertNotNull(file)
    assertEquals(listOf(ImageGenClient.PORTRAIT_SIZE), sizes.toList())
  }

  @Test
  fun `a refused size falls back to the default frame once`() = runTest {
    val gen = generator {
      if (sizes.size == 1) {
        json("""{"error":{"message":"Unsupported value","type":"invalid_request_error","param":"size","code":null}}""", HttpStatusCode.BadRequest)
      } else {
        json(ok)
      }
    }

    val file = gen.draw("agent-1", "Water", brief, dark = false)

    assertNotNull(file)
    assertEquals(listOf(ImageGenClient.PORTRAIT_SIZE, ImageGenClient.IMAGE_SIZE), sizes.toList())
  }

  @Test
  fun `a content refusal is not retried in another size`() = runTest {
    val gen = generator {
      json(
        """{"error":{"message":"The image size you asked for is fine, the prompt is not","type":"invalid_request_error","param":"prompt","code":"content_policy_violation"}}""",
        HttpStatusCode.BadRequest,
      )
    }

    assertNull(gen.draw("agent-1", "Water", brief, dark = false))
    // The message even says "size": the param names the field, and it is not the size.
    assertEquals(listOf(ImageGenClient.PORTRAIT_SIZE), sizes.toList())
  }

  @Test
  fun `a drawing cancelled while Meta draws is still recorded and leaves no file`() = runTest {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val gen = generator {
      started.complete(Unit)
      release.await()
      json(ok)
    }

    val drawing = async(kotlinx.coroutines.Dispatchers.Default) { gen.draw("agent-1", "Water", brief, dark = true) }
    started.await()
    drawing.cancel()
    release.complete(Unit)
    runCatching { drawing.await() }

    assertEquals("the picture Meta drew still reaches the spend card", 1, usage.size)
    assertTrue("no file for a sheet that was closed", dir.listFiles().orEmpty().isEmpty())
  }

  @Test
  fun `a new drawing replaces the old file and discard removes it`() = runTest {
    val gen = generator { json(ok) }
    val first = gen.draw("agent-1", "Water", brief, dark = true)!!
    Thread.sleep(2) // the file name carries the time
    val second = gen.draw("agent-1", "Water", brief, dark = true)!!
    val other = gen.draw("agent-2", "Budget", brief, dark = true)!!

    assertTrue(!first.exists() && second.exists())

    gen.discard("agent-1")

    assertTrue(!second.exists())
    assertTrue("another agent's mockup is not touched", other.exists())
  }
}
