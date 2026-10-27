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
 * The lesson cursor (which step is shown) must live on the conversation as explicit state, NOT be derived
 * from the number of assistant messages. Otherwise a free question to the LLM during a lesson (an extra
 * assistant answer) shifts the cursor and the lesson "jumps over" steps. This test pins the cursor's
 * independence from the message count.
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

    // Free questions during a lesson add assistant messages — the cursor does not depend on them.
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
