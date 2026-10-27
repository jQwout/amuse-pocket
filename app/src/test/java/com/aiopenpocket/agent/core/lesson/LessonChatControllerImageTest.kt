package com.aiopenpocket.agent.core.lesson

import android.app.Application
import android.util.Base64
import androidx.room.Room
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.NoOpLlmUsageRecorder
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.data.database.AppDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * An image step with `imagePrompt`: where the image comes from and what happens when drawing fails.
 *
 * The lesson contract "1 step = exactly 1 assistant message" must hold for any generation outcome —
 * otherwise the lesson cursor drifts away from the history.
 *
 * `runBlocking`, not `runTest`: the step waits for the image under `withTimeoutOrNull`, and generation runs
 * on real IO. The virtual time of `runTest` would skip the timeout instantly while the request is still in
 * flight, and the test would check the fallback instead of the image.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LessonChatControllerImageTest {

  private val application = RuntimeEnvironment.getApplication() as Application
  private lateinit var db: AppDatabase
  private lateinit var repo: ConversationRepository
  private val calls = AtomicInteger(0)
  private val sentBodies = mutableListOf<String>()

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repo = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
  }

  @After
  fun tearDown() {
    db.close()
    File(application.filesDir, "lesson_images").deleteRecursively()
  }

  private fun controller(fail: Boolean = false): LessonChatController {
    val engine = MockEngine { request ->
      calls.incrementAndGet()
      synchronized(sentBodies) { sentBodies += (request.body as TextContent).text }
      if (fail) {
        respondError(HttpStatusCode.InternalServerError)
      } else {
        val b64 = Base64.encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), Base64.NO_WRAP)
        respond("""{"data":[{"b64_json":"$b64"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
      }
    }
    val generator = LessonImageGenerator(application, ImageGenClient(engine, { "k" }, NoOpLlmUsageRecorder))
    return LessonChatController(repo, generator, VideoUnderstandingClient(engine, { "k" }, NoOpLlmUsageRecorder))
  }

  private fun lessonOf(step: LessonStep) = Lesson(id = "l", title = "Урок", steps = listOf(step))

  private suspend fun postedParams(conversationId: String): JSONObject {
    val message = repo.getAllMessages(conversationId).single()
    return JSONObject(message.cardSpec!!).getJSONObject("params")
  }

  @Test
  fun `an image step without its own asset shows the generated picture`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val step = LessonStep(type = "image", imagePrompt = "робот читает книгу", caption = "Пакет")

    controller().postStep(id, lessonOf(step), 0)

    val params = postedParams(id)
    assertEquals("", params.getString("asset"))
    assertTrue(params.getString("url"), params.getString("url").startsWith("file://"))
    assertTrue(File(java.net.URI(params.getString("url"))).isFile)
    assertEquals("Пакет", params.getString("caption"))
    assertEquals(1, calls.get())
  }

  /** The bundled asset works offline and costs nothing — with it there is no reason to draw. */
  @Test
  fun `a bundled asset wins and nothing is generated`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val step = LessonStep(type = "image", asset = "lessons/images/llm_path.svg", imagePrompt = "робот читает книгу")

    controller().postStep(id, lessonOf(step), 0)

    val params = postedParams(id)
    assertEquals("lessons/images/llm_path.svg", params.getString("asset"))
    assertEquals("", params.getString("url"))
    assertEquals(0, calls.get())
  }

  @Test
  fun `a failed generation still posts exactly one step, falling back to the step url`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val step = LessonStep(type = "image", imagePrompt = "робот читает книгу", url = "https://example.com/x.png")

    controller(fail = true).postStep(id, lessonOf(step), 0)

    assertEquals("https://example.com/x.png", postedParams(id).getString("url"))
  }

  /** The prompt goes into the card, otherwise the "Draw it for me" button has nothing to redraw. */
  @Test
  fun `an illustration step carries its prompt for draw-it-for-me`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val step = LessonStep(type = "image", asset = "lessons/images/llm_path.svg", imagePrompt = "робот читает книгу")

    controller().postStep(id, lessonOf(step), 0)

    assertEquals("робот читает книгу", postedParams(id).getString("image_prompt"))
  }

  @Test
  fun `a step without a prompt offers no redraw`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)

    controller().postStep(id, lessonOf(LessonStep(type = "image", asset = "lessons/images/llm_path.svg")), 0)

    assertTrue(!postedParams(id).has("image_prompt"))
  }

  /**
   * The "for me" image is a separate message under the step, drawn from the step's prompt and "about me".
   * It has no prompt of its own and therefore no button: there is no point redrawing a redrawn image.
   */
  @Test
  fun `draw-it-for-me posts a personalized picture as its own card`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)

    val posted = controller().postPersonalizedImage(id, "A robot reads a book.", "Пакет", "Пеку хлеб", "ru")

    assertTrue(posted)
    val params = postedParams(id)
    assertTrue(params.getString("url"), params.getString("url").startsWith("file://"))
    assertEquals("Пакет — нарисовано под тебя", params.getString("caption"))
    assertTrue(!params.has("image_prompt"))
    val sent = sentBodies.single()
    assertTrue(sent, sent.contains("A robot reads a book."))
    assertTrue(sent, sent.contains("Пеку хлеб"))
  }

  @Test
  fun `a failed redraw posts nothing`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)

    val posted = controller(fail = true).postPersonalizedImage(id, "A robot reads a book.", "Пакет", "Пеку хлеб", "ru")

    assertTrue(!posted)
    assertTrue(repo.getAllMessages(id).isEmpty())
  }

  /** Preloading draws ahead of time, and showing the step takes the image from the cache without a new request. */
  @Test
  fun `prefetched images make showing the step free`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val lesson = Lesson(
      id = "l",
      steps = listOf(
        LessonStep(type = "text", text = "Сначала текст"),
        LessonStep(type = "image", imagePrompt = "робот читает книгу"),
        LessonStep(type = "image", imageKey = "llm_path", imagePrompt = "этот не рисуем: есть ассет"),
      ),
    )
    val controller = controller()

    controller.prefetchImages(lesson)
    assertEquals(1, calls.get())

    controller.postStep(id, lesson, 1)
    assertEquals(1, calls.get())
    assertTrue(postedParams(id).getString("url").startsWith("file://"))
  }

  /**
   * Not an image, but the same "step → cardSpec" path: a fill step's accepted variants must reach the card,
   * otherwise a learner with a correct synonym gets stuck again. Empty variants are dropped already here.
   */
  @Test
  fun `a fill step carries its accepted answers into the card`() = runBlocking {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    val step = LessonStep(type = "fill", template = "В запросе нет ___", answer = "конкретики", answers = listOf("деталей", " "))

    controller().postStep(id, lessonOf(step), 0)

    val params = postedParams(id)
    assertEquals("конкретики", params.getString("answer"))
    val answers = params.getJSONArray("answers")
    assertEquals(1, answers.length())
    assertEquals("деталей", answers.getString(0))
  }
}
