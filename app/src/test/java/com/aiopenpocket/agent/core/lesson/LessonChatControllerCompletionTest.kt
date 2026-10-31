package com.aiopenpocket.agent.core.lesson

// @trace english-ui-on-the-demo-path-223241

import android.app.Application
import androidx.room.Room
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.agent.core.llm.NoOpLlmUsageRecorder
import com.aiopenpocket.agent.core.llm.SegmentationClient
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.DrivenUiEngine
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The "lesson complete" card names the lesson in the interface language.
 *
 * The bug: the card used `lesson.title`, and a lesson without an English copy is loaded from the Russian
 * file, so the English UI read "Lesson “<Russian title>” complete!". Here the lesson arrives exactly like
 * that, with a Russian title, and the card must still come out in the interface language.
 */
@RunWith(RobolectricTestRunner::class)
// The plain Application, not AiOpenPocketApp: the app mirrors the stored language into AppLanguage from a
// coroutine, and on a slow first test that write landed AFTER this test set English and flipped it back.
@Config(sdk = [36], application = Application::class)
class LessonChatControllerCompletionTest {

  private val application = RuntimeEnvironment.getApplication() as Application
  private lateinit var db: AppDatabase
  private lateinit var repo: ConversationRepository

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(application, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repo = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
  }

  @After
  fun tearDown() {
    AppLanguage.code = AppLanguage.RU
    db.close()
  }

  private fun controller(): LessonChatController {
    val unused = MockEngine { error("a completion card calls no model") }
    return LessonChatController(
      repo,
      LessonImageGenerator(application, ImageGenClient(unused, { "k" }, NoOpLlmUsageRecorder)),
      VideoUnderstandingClient(unused, { "k" }, NoOpLlmUsageRecorder),
      SegmentationClient(unused, { "k" }, NoOpLlmUsageRecorder),
    )
  }

  private suspend fun completionTitle(lesson: Lesson): String {
    val id = repo.createConversation(title = "Lesson", isTutorial = true)
    controller().postCompletion(id, lesson)
    val card = DrivenUiEngine.parseJsonString(repo.getAllMessages(id).single().cardSpec!!)
    return (card as DrivenUiCard.LessonCompleteCard).title
  }

  @Test
  fun `English UI names a lesson loaded from the Russian file in English`() = runBlocking {
    AppLanguage.code = AppLanguage.EN

    val title = completionTitle(Lesson(id = "react_loop", title = "Цикл агента: ReAct"))

    assertEquals("Lesson “The agent loop: ReAct” complete!", title)
  }

  @Test
  fun `Russian UI keeps the Russian name`() = runBlocking {
    AppLanguage.code = AppLanguage.RU

    val title = completionTitle(Lesson(id = "react_loop", title = "Цикл агента: ReAct"))

    assertEquals("Урок «Цикл агента: ReAct» пройден!", title)
  }

  /** A lesson the roadmap has never heard of still gets a card, named by its own title. */
  @Test
  fun `an unknown lesson id falls back to the lesson's own title`() = runBlocking {
    AppLanguage.code = AppLanguage.EN

    val title = completionTitle(Lesson(id = "lesson_from_the_future", title = "Future lesson"))

    assertEquals("Lesson “Future lesson” complete!", title)
  }
}
