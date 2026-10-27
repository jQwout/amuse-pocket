package com.aiopenpocket.agent.core.prompt

// @trace multi-chat-conversations-b3c93b

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A lesson chat keeps the lesson id in its title: that is how the lesson is found on re-entry. A question
 * in the middle of a lesson is an ordinary turn with an annotation after the answer, and the annotation
 * overwrote the title with the topic of the conversation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationAnnotatorTutorialTest {

  private lateinit var db: AppDatabase
  private lateinit var repository: ConversationRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    repository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
  }

  @After
  fun tearDown() = db.close()

  private class CountingLlm : LlmClient {
    var calls = 0

    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      calls++
      return flowOf(
        StreamEvent.TextDelta("""{"title":"Тема разговора","summary":"Кратко","tags":["a"]}"""),
        StreamEvent.Finish("stop"),
      )
    }
  }

  @Test
  fun `lesson chat keeps its lesson id title and skips the annotation call`() = runTest {
    val conversationId = repository.createConversation(title = "tokens", isTutorial = true)
    repository.addMessage(conversationId, "user", "а что такое токен?")
    repository.addMessage(conversationId, "assistant", "кусочек текста")
    val llm = CountingLlm()

    ConversationAnnotator(llm, repository) { LlmProvider.OPENROUTER }.annotate(conversationId)

    assertEquals("tokens", repository.getConversation(conversationId)?.title)
    assertEquals(0, llm.calls)
    // The lesson is still found by its id.
    assertNotNull(repository.findLatestTutorialConversation("tokens"))
  }

  @Test
  fun `regular chat still gets its title from the annotation`() = runTest {
    val conversationId = repository.createConversation(title = "Новый чат")
    repository.addMessage(conversationId, "user", "привет")
    repository.addMessage(conversationId, "assistant", "здравствуй")

    ConversationAnnotator(CountingLlm(), repository) { LlmProvider.OPENROUTER }.annotate(conversationId)

    assertEquals("Тема разговора", repository.getConversation(conversationId)?.title)
  }
}
