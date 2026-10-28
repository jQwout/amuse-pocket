package com.aiopenpocket.agent.core.notification

// @trace approval-outcome-honesty-554a8c
// @trace fonovye-hody-agenta-dde42d

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.AgentEvent
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnExecutor
import com.aiopenpocket.agent.loop.TurnLifeSupport
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnPersistence
import com.aiopenpocket.i18n.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The whole chain behind "the agent needs your permission", down to the notification the system shade
 * actually holds: a turn asks for approval → [TurnManager] sees the app is not on screen → the real
 * [AgentTurnNotifier] → the real [showAgentNotification].
 *
 * [com.aiopenpocket.agent.loop.TurnManagerTest] covers WHEN the manager decides to nudge (including
 * the case this feature was written for: the user minimizes the app only after the request appeared).
 * This test covers THAT IT ARRIVES, because on Android 14 it did not: the card lived on the chat
 * screen only, the shade showed «The agent is replying…» the whole time, and the request expired with
 * nobody asked.
 */
// application = Application::class on purpose: the real AiOpenPocketApp re-reads the stored language
// into AppLanguage from a background collector (mirrorInterfaceLanguage), and that write lands in the
// middle of a test and puts the interface back to Russian under it. A bare Application has no such
// collector, and nothing here needs the DI graph.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher
class PendingApprovalPushTest {

  private object NoopLog : AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  /** One turn that asks for permission and then waits — exactly as a real turn does while suspended on the coordinator. */
  private class ApprovalAskingExecutor(private val request: ToolApprovalRequest) : TurnExecutor {
    override fun execute(
      conversationId: String,
      userMessage: String,
      provider: LlmProvider,
      mode: AgentMode,
      planId: String?,
      budget: PlanRunBudget,
      systemPromptExtra: String?,
      executionMode: ExecutionMode,
    ): Flow<AgentEvent> = flow {
      emit(AgentEvent.ToolApprovalRequired(request))
      awaitCancellation()
    }
  }

  private class FakePersistence(private val title: String) : TurnPersistence {
    override suspend fun addUserMessage(conversationId: String, text: String) = "u1"
    override suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?) = "a1"
    override suspend fun conversationTitle(conversationId: String): String = title
  }

  private object NoLifeSupport : TurnLifeSupport {
    // false = the system refused protection, the usual case for a turn running in the background. The
    // turn still runs, which is the situation this push exists for.
    override suspend fun acquire(conversationId: String) = false
    override fun release(conversationId: String) = Unit
    override fun onProtectionLost(handler: (Set<String>) -> Unit) = Unit
  }

  private lateinit var application: Application

  private val request = ToolApprovalRequest(
    requestId = "req-1",
    runId = "run-1",
    conversationId = "c1",
    toolName = "email",
    riskClass = RiskClass.EXTERNAL,
    arguments = mapOf<String, JsonElement>("action" to JsonPrimitive("compose")),
  )

  @Before
  fun setUp() {
    application = ApplicationProvider.getApplicationContext()
    shadowOf(application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
  }

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private fun notifications() =
    shadowOf(application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).allNotifications

  // The manager's scope rides the test scheduler (as in TurnManagerTest), and its Job comes from
  // backgroundScope so the waiting turn is killed when the test ends.
  private fun TestScope.managerInBackground() = TurnManager(
    executor = ApprovalAskingExecutor(request),
    persistence = FakePersistence("Masha"),
    foreground = { false },
    notifier = AgentTurnNotifier(application),
    pendingApprovals = { it == request.requestId },
    lifeSupport = NoLifeSupport,
    scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    monotonicNowMs = { 100_000L },
    selectedProvider = { LlmProvider.OPENROUTER },
    log = NoopLog,
  )

  @Test
  fun `a turn waiting for permission in the background puts it in the shade, in English`() = runTest {
    AppLanguage.code = AppLanguage.EN

    managerInBackground().launchTurn("c1", "email Masha the meeting moves to Friday", AgentMode.CHAT)
    advanceTimeBy(100)

    val shade = shadowOf(notifications().single())
    // The title is the chat, because tapping it is what opens that chat — where the Allow/Decline bar is.
    assertEquals("Masha", shade.contentTitle.toString())
    assertEquals(
      "The agent needs your permission: Email: write a draft. Open the chat to answer.",
      shade.contentText.toString(),
    )
  }

  /** The Russian side is still there: a one-sided move to tr() would make the Russian app English. */
  @Test
  fun `the same push is Russian in the Russian interface`() = runTest {
    AppLanguage.code = AppLanguage.RU

    managerInBackground().launchTurn("c1", "напиши Маше, что встреча в пятницу", AgentMode.CHAT)
    advanceTimeBy(100)

    val shade = shadowOf(notifications().single())
    assertEquals(
      "Агенту нужно разрешение: Почта: написать письмо. Открой чат, чтобы ответить.",
      shade.contentText.toString(),
    )
  }

  /** A notification with nothing to tap is a dead end: it must lead back to the chat that is asking. */
  @Test
  fun `the push can be tapped`() = runTest {
    AppLanguage.code = AppLanguage.EN

    managerInBackground().launchTurn("c1", "email Masha the meeting moves to Friday", AgentMode.CHAT)
    advanceTimeBy(100)

    assertTrue("with no contentIntent there is nowhere to tap", notifications().single().contentIntent != null)
  }
}
