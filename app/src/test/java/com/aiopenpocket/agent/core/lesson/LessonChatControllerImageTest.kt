package com.aiopenpocket.agent.core.lesson

import android.app.Application
import android.util.Base64
import androidx.room.Room
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.NoOpLlmUsageRecorder
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.data.database.AppDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
 * Шаг-картинка с `imagePrompt`: откуда берётся картинка и что происходит, когда нарисовать не вышло.
 *
 * Контракт урока «1 шаг = ровно 1 сообщение ассистента» должен держаться при любом исходе генерации —
 * иначе курсор урока разъедется с историей.
 *
 * `runBlocking`, а не `runTest`: шаг ждёт картинку под `withTimeoutOrNull`, а генерация идёт на
 * настоящем IO. Виртуальное время `runTest` промотало бы таймаут мгновенно, пока запрос ещё в пути,
 * и тест проверял бы фолбэк вместо картинки.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LessonChatControllerImageTest {

  private val application = RuntimeEnvironment.getApplication() as Application
  private lateinit var db: AppDatabase
  private lateinit var repo: ConversationRepository
  private val calls = AtomicInteger(0)

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
    val engine = MockEngine {
      calls.incrementAndGet()
      if (fail) {
        respondError(HttpStatusCode.InternalServerError)
      } else {
        val b64 = Base64.encodeToString(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47), Base64.NO_WRAP)
        respond("""{"data":[{"b64_json":"$b64"}]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
      }
    }
    val generator = LessonImageGenerator(application, ImageGenClient(engine, { "k" }, NoOpLlmUsageRecorder))
    return LessonChatController(repo, generator)
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

  /** Бандл работает без сети и ничего не стоит — при нём рисовать незачем. */
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

  /** Предзагрузка рисует заранее, и показ шага берёт картинку из кеша без нового запроса. */
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
}
