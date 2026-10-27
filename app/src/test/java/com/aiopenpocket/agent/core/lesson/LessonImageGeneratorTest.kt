package com.aiopenpocket.agent.core.lesson

import android.app.Application
import android.util.Base64
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.NoOpLlmUsageRecorder
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The lesson image cache. Every generation costs money and tens of seconds, so the main thing is never to
 * draw the same thing twice: neither on a repeated show nor on a simultaneous request from preloading and
 * the step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LessonImageGeneratorTest {

  private val application = RuntimeEnvironment.getApplication() as Application
  private val image = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9, 9)
  private val calls = AtomicInteger(0)
  private val sentPrompts = mutableListOf<String>()

  @After
  fun tearDown() {
    File(application.filesDir, "lesson_images").deleteRecursively()
  }

  private fun generator(failFirst: Int = 0, latencyMs: Long = 0): LessonImageGenerator {
    val engine = MockEngine { request ->
      val n = calls.incrementAndGet()
      synchronized(sentPrompts) { sentPrompts += (request.body as TextContent).text }
      if (latencyMs > 0) delay(latencyMs)
      if (n <= failFirst) {
        respondError(HttpStatusCode.InternalServerError)
      } else {
        respond(
          """{"data":[{"b64_json":"${Base64.encodeToString(image, Base64.NO_WRAP)}"}]}""",
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
    }
    return LessonImageGenerator(application, ImageGenClient(engine, { "k" }, NoOpLlmUsageRecorder))
  }

  @Test
  fun `a generated image is cached and reused without another request`() = runTest {
    val generator = generator()

    val first = generator.imageFor("робот читает книгу")
    val second = generator.imageFor("робот читает книгу")

    assertNotNull(first)
    assertEquals(first, second)
    assertArrayEquals(image, first!!.readBytes())
    assertEquals(1, calls.get())
  }

  /** A failure must leave neither a broken file nor a "remembered" failure: the next show tries again. */
  @Test
  fun `a failed generation leaves nothing behind and is retried next time`() = runTest {
    val generator = generator(failFirst = 1)

    assertNull(generator.imageFor("робот читает книгу"))
    val leftovers = File(application.filesDir, "lesson_images").listFiles().orEmpty()
    assertTrue("после сбоя не должно остаться файлов: ${leftovers.map { it.name }}", leftovers.isEmpty())

    assertNotNull(generator.imageFor("робот читает книгу"))
    assertEquals(2, calls.get())
  }

  /** Without a shared style every step would look like it came from a different book, and the model would draw crooked letters. */
  @Test
  fun `every prompt carries the shared lesson style`() = runTest {
    generator().imageFor("робот читает книгу")

    val sent = sentPrompts.single()
    assertTrue(sent, sent.contains("робот читает книгу"))
    assertTrue(sent, sent.contains("No text, letters or numbers"))
    assertTrue(sent, sent.contains("16:9"))
  }

  @Test
  fun `different prompts are different pictures`() = runTest {
    val generator = generator()

    val a = generator.imageFor("робот читает книгу")
    val b = generator.imageFor("кот на клавиатуре")

    assertTrue(a != b)
    assertEquals(2, calls.get())
  }

  /** Lesson preloading and showing a step may ask for the same frame at once — we pay once. */
  @Test
  fun `concurrent requests for one prompt generate it once`() = runTest {
    val generator = generator(latencyMs = 200)

    val results = listOf(
      async { generator.imageFor("робот читает книгу") },
      async { generator.imageFor("робот читает книгу") },
    ).awaitAll()

    assertEquals(1, calls.get())
    assertNotNull(results[0])
    assertEquals(results[0], results[1])
  }
}
