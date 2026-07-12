package com.aiopenpocket.agent.loop

// @trace rezhim-refleksii-fca3c4

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.prompt.AssetSource
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проверяет гейт пустого финала REFLECT (находка код-ревью): revise без текста и без карточки должен
 * дать терминальную ошибку + «Повторить», а не персиститься тремя декоративными заголовками
 * ✍/🔍/✨, которые делают transcript непустым и обошли бы blank-гейт resolveCompletedTurn.
 *
 * Настоящий [ReactLoopRunner] + [PrePromptAssembler] на in-memory Room; LLM — скриптовый фейк,
 * отдающий по одному потоку на вызов (draft → critique → revise).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReflectionOrchestratorTest {

  private object NoopLog : AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  /** Отдаёт по одному скрипту событий на каждый streamChat: [0]=draft, [1]=critique, [2]=revise. */
  private class ScriptedLlm(private val perCall: List<List<StreamEvent>>) : LlmClient {
    var calls = 0
      private set

    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> {
      val events = perCall.getOrElse(calls) { listOf(StreamEvent.Finish("stop")) }
      calls++
      return flowOf(*events.toTypedArray())
    }
  }

  private lateinit var context: Context
  private lateinit var db: AppDatabase

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = db.close()

  private fun orchestrator(llm: ScriptedLlm): ReflectionOrchestrator {
    val memoryRepository = MemoryRepository(db.factDao(), db.episodeDao())
    val conversationRepository = ConversationRepository(db.conversationDao(), db.conversationTagDao(), db.messageDao())
    val assembler = PrePromptAssembler(
      assets = AssetSource { null },
      memoryRepository = memoryRepository,
      conversationRepository = conversationRepository,
      skillRegistry = SkillRegistry(emptySet()),
      userPreferencesRepository = UserPreferencesRepository(context),
      agentResolver = DefaultAgentResolver(UserAgentRepository(db, db.userAgentDao())),
    )
    val reactLoopRunner = ReactLoopRunner(
      llm,
      ToolExecutor(ToolRegistry(emptySet())),
      ToolRegistry(emptySet()),
      memoryRepository,
      NoopLog,
    )
    return ReflectionOrchestrator(
      llm,
      ToolRegistry(emptySet()),
      assembler,
      reactLoopRunner,
      ConversationAnnotator(llm, conversationRepository),
      NoopLog,
    )
  }

  private fun textThenStop(text: String) = listOf(StreamEvent.TextDelta(text), StreamEvent.Finish("stop"))
  private val justStop = listOf(StreamEvent.Finish("stop"))

  @Test
  fun `пустой финал revise даёт Error и НЕ Completed`() = runTest {
    // draft и critique непусты, но revise возвращает пустоту — realistic «правка ничего не дала».
    val llm = ScriptedLlm(listOf(textThenStop("черновой ответ"), textThenStop("критика"), justStop))

    val events = orchestrator(llm).execute("c1", "вопрос").toList()

    val error = events.filterIsInstance<AgentEvent.Error>().singleOrNull()
    assertEquals(TurnTexts.BLANK_REPLY_ERROR, error?.message)
    assertNull("пустой финал не должен персиститься как Completed", events.filterIsInstance<AgentEvent.Completed>().firstOrNull())
  }

  @Test
  fun `непустой финал revise даёт Completed с транскриптом`() = runTest {
    val llm = ScriptedLlm(listOf(textThenStop("черновой ответ"), textThenStop("критика"), textThenStop("Итоговый ответ")))

    val events = orchestrator(llm).execute("c1", "вопрос").toList()

    val completed = events.filterIsInstance<AgentEvent.Completed>().singleOrNull()
    assertTrue("финальный ответ должен попасть в транскрипт", completed?.result?.text?.contains("Итоговый ответ") == true)
    assertNull("успешный ход не должен эмитить Error", events.filterIsInstance<AgentEvent.Error>().firstOrNull())
  }
}
