package com.aiopenpocket.agent.core.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Курсор урока (какой шаг показан) должен жить на диалоге как явное состояние, а НЕ выводиться из
 * числа assistant-сообщений. Иначе свободный вопрос к LLM во время урока (лишний assistant-ответ)
 * сдвигает курсор и урок «перепрыгивает» шаги. Этот тест фиксирует независимость курсора от
 * количества сообщений.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LessonCursorPersistenceTest {

  private lateinit var db: AppDatabase
  private lateinit var repo: ConversationRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repo = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun `lesson cursor persists independent of message count`() = runTest {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    repo.updateLessonCursor(id, stepIndex = 3, finished = false)

    // Свободные вопросы во время урока добавляют assistant-сообщения — курсор от них не зависит.
    repeat(5) { repo.addMessage(id, "assistant", "ответ на вопрос") }

    val loaded = repo.getConversation(id)
    assertEquals(3, loaded?.lessonStepIndex)
    assertEquals(false, loaded?.lessonFinished)
  }

  @Test
  fun `lesson finished flag round-trips`() = runTest {
    val id = repo.createConversation(title = "Урок", isTutorial = true)
    repo.updateLessonCursor(id, stepIndex = 8, finished = true)

    val loaded = repo.getConversation(id)
    assertEquals(8, loaded?.lessonStepIndex)
    assertEquals(true, loaded?.lessonFinished)
  }
}
