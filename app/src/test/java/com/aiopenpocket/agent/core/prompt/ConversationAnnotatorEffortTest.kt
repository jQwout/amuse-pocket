package com.aiopenpocket.agent.core.prompt

// @trace the-prompt-cache-keeps-the-whole-chat-history-d25062

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.llm.AgentModel
import com.aiopenpocket.agent.core.llm.AgentModelContext
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.ReasoningEffort
import com.aiopenpocket.agent.core.llm.ReasoningEffortContext
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A chat's title and tags are a few words of JSON: the call that writes them runs on Spark at low effort, not
 * at the model's own effort on whatever model the person picked for their agents.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationAnnotatorEffortTest {

  private lateinit var db: AppDatabase

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = db.close()

  private class RecordingLlm : LlmClient {
    var effort: ReasoningEffort? = null
    var model: String? = null

    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> = flow {
      // Read where the real client reads it: in the collecting coroutine.
      effort = currentCoroutineContext()[ReasoningEffortContext]?.effort
      model = currentCoroutineContext()[AgentModelContext]?.model
      emit(StreamEvent.TextDelta("""{"title":"Topic","summary":"Short","tags":["a"]}"""))
      emit(StreamEvent.Finish("stop"))
    }
  }

  @Test
  fun `the title is written by Spark at low effort`() = runTest {
    val repository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
    val chat = repository.createConversation(title = "Test")
    repository.addMessage(chat, "user", "hi")
    repository.addMessage(chat, "assistant", "hello")
    val llm = RecordingLlm()

    ConversationAnnotator(llm, repository) { LlmProvider.META }.annotate(chat)

    assertEquals(ReasoningEffort.LOW, llm.effort)
    assertEquals(AgentModel.SPARK.key, llm.model)
  }
}
