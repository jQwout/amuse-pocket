package com.aiopenpocket.agent.core.prompt

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Аннотация диалога уходит ВЫБРАННОМУ вендору.
 *
 * Из двух мест, что молча ехали на дефолт `LlmProvider.NIM`, это было опаснее: аннотация идёт после
 * КАЖДОЙ реплики, а её тело — дословный кусок переписки. Дефолт убран, так что «забыл прокинуть»
 * теперь не компилируется, — но от «прокинул литерал» компилятор не спасает.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationAnnotatorProviderTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = db.close()

  private class RecordingLlm : LlmClient {
    val providers = mutableListOf<LlmProvider>()

    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      providers += provider
      return flowOf(
        StreamEvent.TextDelta("""{"title":"Тема","summary":"Кратко","tags":["a"]}"""),
        StreamEvent.Finish("stop"),
      )
    }
  }

  @Test
  fun `аннотация диалога идёт к выбранному провайдеру, а не к NIM по умолчанию`() = runTest {
    val repository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
    val conversationId = repository.createConversation(title = "Test")
    repository.addMessage(conversationId, "user", "привет")
    repository.addMessage(conversationId, "assistant", "здравствуй")
    val llm = RecordingLlm()

    ConversationAnnotator(llm, repository) { LlmProvider.OPENROUTER }.annotate(conversationId)

    assertEquals(listOf(LlmProvider.OPENROUTER), llm.providers)
  }
}
