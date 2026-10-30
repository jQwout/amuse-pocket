package com.aiopenpocket.agent.core.memory.repository

// @trace ai-academy-onboarding-5d51bb
// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lesson quiz's chosen answers live on the quiz message (messages.cardState). Reopening the lesson reads the
 * messages from the database again, so this is the storage half of "answers survive leaving the lesson".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageCardStatePersistenceTest {

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
  fun `quiz answers saved on the message are read back when the lesson is reopened`() = runTest {
    val convId = repo.createConversation(title = "lesson", isTutorial = true)
    val spec = """{"template":"lesson_quiz","params":{"questions":[]}}"""
    val quizId = repo.addMessage(convId, "assistant", "", cardSpec = spec)

    repo.updateMessageCardState(quizId, "0:2,1:0")

    val reopened = repo.getAllMessages(convId).single { it.id == quizId }
    assertEquals("0:2,1:0", reopened.cardState)
    // The authored card is untouched: only the person's state was written.
    assertEquals(spec, reopened.cardSpec)
  }

  @Test
  fun `a message nobody answered has no card state and a retake clears it`() = runTest {
    val convId = repo.createConversation(title = "lesson", isTutorial = true)
    val quizId = repo.addMessage(convId, "assistant", "", cardSpec = "{}")
    assertNull(repo.getAllMessages(convId).single().cardState)

    repo.updateMessageCardState(quizId, "0:1")
    repo.updateMessageCardState(quizId, null)

    assertNull(repo.getAllMessages(convId).single().cardState)
  }

  @Test
  fun `answers of one quiz do not leak into another message`() = runTest {
    val convId = repo.createConversation(title = "lesson", isTutorial = true)
    val first = repo.addMessage(convId, "assistant", "", cardSpec = "{}")
    val second = repo.addMessage(convId, "assistant", "", cardSpec = "{}")

    repo.updateMessageCardState(first, "0:1")

    val byId = repo.getAllMessages(convId).associateBy { it.id }
    assertEquals("0:1", byId.getValue(first).cardState)
    assertNull(byId.getValue(second).cardState)
  }
}
