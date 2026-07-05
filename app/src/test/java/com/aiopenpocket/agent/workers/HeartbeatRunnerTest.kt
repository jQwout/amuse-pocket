package com.aiopenpocket.agent.workers

// @trace heartbeat-napominalki-agenta-5f0442

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.loop.ReactLoopRunner
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeartbeatRunnerTest {

  private class FakeLlm(private val events: List<StreamEvent>) : LlmClient {
    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> = flowOf(*events.toTypedArray())
  }

  private class FakeNotifier : TurnNotifier {
    data class Call(val conversationId: String, val title: String?, val preview: String, val failed: Boolean)
    val calls = mutableListOf<Call>()
    override fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean) {
      calls += Call(conversationId, conversationTitle, preview, failed)
    }
  }

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var notifier: FakeNotifier

  @Before
  fun setUp() = runTest {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    notifier = FakeNotifier()
    // FK: MessageEntity.conversationId -> ConversationEntity.id. Диалог должен существовать ДО
    // того, как ConversationRepository.addMessage попытается в него что-то записать.
    db.conversationDao().insert(ConversationEntity(id = "conv1", title = "T"))
  }

  @After
  fun tearDown() = db.close()

  private fun runner(events: List<StreamEvent>): HeartbeatRunner {
    val reactLoopRunner = ReactLoopRunner(
      FakeLlm(events),
      ToolExecutor(ToolRegistry(emptySet())),
      MemoryRepository(db.factDao(), db.episodeDao()),
    )
    val conversationRepository = ConversationRepository(
      db.conversationDao(),
      db.conversationTagDao(),
      db.messageDao(),
    )
    return HeartbeatRunner(
      db.heartbeatDao(),
      reactLoopRunner,
      ToolRegistry(emptySet()),
      conversationRepository,
      notifier,
    )
  }

  private fun hb(mode: String, prompt: String? = null, kind: String = "one_time") = HeartbeatEntity(
    id = "hb1", title = "T", mode = mode, prompt = prompt, message = "msg",
    conversationId = "conv1", kind = kind, intervalMinutes = if (kind == "periodic") 30 else null,
  )

  @Test
  fun `unknown or cancelled heartbeat is a no-op DONE`() = runTest {
    val runner = runner(emptyList())
    assertEquals(FireOutcome.DONE, runner.handleFire("nope", attempt = 0))
  }

  @Test
  fun `push mode shows notification and records fired`() = runTest {
    db.heartbeatDao().insert(hb(mode = "push"))
    val runner = runner(emptyList())
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    // Пуш ушёл через общий нотифаер, с заголовком напоминания и deep-link в его диалог.
    assertEquals("T", notifier.calls.single().title)
    assertEquals("conv1", notifier.calls.single().conversationId)
  }

  @Test
  fun `agent mode with prompt writes assistant message to conversation`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь задачи"))
    val runner = runner(listOf(StreamEvent.TextDelta("Всё в порядке, задач 3."), StreamEvent.Finish("stop")))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    val messages = db.messageDao().getAllOrdered("conv1")
    assertTrue(messages.any { it.role == "assistant" && it.content.contains("задач 3") })
    assertEquals("Всё в порядке, задач 3.", db.heartbeatDao().getById("hb1")?.lastResult)
    assertTrue(notifier.calls.single().preview.contains("задач 3")) // ответ ушёл и пушем
  }

  @Test
  fun `pulse HEARTBEAT_OK stays silent - no message`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = null))
    val runner = runner(listOf(StreamEvent.TextDelta("HEARTBEAT_OK"), StreamEvent.Finish("stop")))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    assertTrue(db.messageDao().getAllOrdered("conv1").isEmpty())
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    assertTrue(notifier.calls.isEmpty()) // HEARTBEAT_OK = полная тишина, пуша тоже нет
  }

  @Test
  fun `llm error retries then marks error`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "x"))
    val runner = runner(listOf(StreamEvent.Error("network down")))
    assertEquals(FireOutcome.RETRY, runner.handleFire("hb1", attempt = 0))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 2))
    assertEquals("error", db.heartbeatDao().getById("hb1")?.status)
  }

  @Test
  fun `periodic keeps status scheduled after successful run`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "x", kind = "periodic"))
    val runner = runner(listOf(StreamEvent.TextDelta("Готово"), StreamEvent.Finish("stop")))
    runner.handleFire("hb1", attempt = 0)
    assertEquals("scheduled", db.heartbeatDao().getById("hb1")?.status)
  }
}
