package com.aiopenpocket.agent.loop

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TurnManager тестируется на скриптовых фейках портов: без LLM, без Room, без Android. */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnManagerTest {

  private object NoopLog : com.aiopenpocket.agent.core.AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }

  private class ScriptedExecutor : TurnExecutor {
    data class Call(
      val convId: String,
      val message: String,
      val mode: AgentMode,
      /** Task 10 (structured-plan-engine-907066): replaces the old `planApproved: Boolean`. */
      val planId: String?,
      val budget: PlanRunBudget = PlanRunBudget(),
      val executionMode: ExecutionMode = ExecutionMode.LIVE,
    )

    /** Провайдеры, с которыми менеджер звал исполнителя, — по одному на ход. */
    val providers = mutableListOf<LlmProvider>()

    val calls = mutableListOf<Call>()
    var cancelledCollections = 0

    /** Скрипт хода: по вызову отдаёт flow событий. По умолчанию — мгновенный пустой финиш. */
    var script: (Call) -> Flow<AgentEvent> = { flowOf(AgentEvent.Completed(finished(""))) }

    override fun execute(
      conversationId: String,
      userMessage: String,
      provider: LlmProvider,
      mode: AgentMode,
      planId: String?,
      budget: PlanRunBudget,
      systemPromptExtra: String?,
      executionMode: ExecutionMode,
    ): Flow<AgentEvent> {
      val call = Call(conversationId, userMessage, mode, planId, budget, executionMode)
      calls += call
      providers += provider
      return script(call).onCompletion { if (it is CancellationException) cancelledCollections++ }
    }
  }

  private class FakePersistence : TurnPersistence {
    data class Row(val id: String, val convId: String, val role: String, val content: String, val cardSpec: String?)

    val rows = mutableListOf<Row>()
    private var nextId = 0

    override suspend fun addUserMessage(conversationId: String, text: String): String {
      val id = "m${nextId++}"
      rows += Row(id, conversationId, "user", text, null)
      return id
    }

    override suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String {
      val id = "m${nextId++}"
      rows += Row(id, conversationId, "assistant", text, cardSpec)
      return id
    }

    override suspend fun conversationTitle(conversationId: String): String? = "Чат $conversationId"
  }

  private class RecordingNotifier : TurnNotifier {
    data class Shown(val convId: String, val title: String?, val preview: String, val failed: Boolean)

    val shown = mutableListOf<Shown>()

    override fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean) {
      shown += Shown(conversationId, conversationTitle, preview, failed)
    }
  }

  private class FakeLifeSupport : TurnLifeSupport {
    /** false — система отказала в защите (типичный случай: старт из фона). */
    var grant = true
    val acquired = mutableListOf<String>()
    val released = mutableListOf<String>()
    private var handler: ((Set<String>) -> Unit)? = null

    override suspend fun acquire(conversationId: String): Boolean {
      if (grant) acquired += conversationId
      return grant
    }

    override fun release(conversationId: String) {
      released += conversationId
    }

    override fun onProtectionLost(handler: (Set<String>) -> Unit) {
      this.handler = handler
    }

    /** Имитирует системный onTimeout сервиса. */
    fun loseProtection(ids: Set<String>) = handler?.invoke(ids)
  }

  private val executor = ScriptedExecutor()
  private val persistence = FakePersistence()
  private val notifier = RecordingNotifier()
  private val lifeSupport = FakeLifeSupport()
  private var inForeground = true

  /** Что «выбрано в Настройках» на момент хода: тест меняет это между ходами. */
  private var selectedProvider = LlmProvider.OPENROUTER

  // Скоуп менеджера на UnconfinedTestDispatcher(testScheduler): ходы исполняются жадно на том же
  // виртуальном планировщике, что и тест, — advanceUntilIdle() догоняет любые задержки. Job берём
  // от backgroundScope, чтобы «вечные» ходы (awaitCancellation) гасились по завершении теста.
  private fun TestScope.manager() = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = { inForeground },
    notifier = notifier,
    lifeSupport = lifeSupport,
    scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    monotonicNowMs = { 100_000L },
    selectedProvider = { selectedProvider },
    log = NoopLog,
  )

  @Test
  fun `завершённый ход пишет user и assistant в БД и даёт Completed с id ответа`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("Привет!"))) }
    val m = manager()

    m.launchTurn("c1", "Здравствуй", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(listOf("user" to "Здравствуй", "assistant" to "Привет!"), persistence.rows.map { it.role to it.content })
    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertEquals(persistence.rows[1].id, status.insertedMessageId)
    assertNull(status.error)
  }

  @Test
  fun `ход живёт без подписчиков — никто не собирает флоу, ответ всё равно в БД`() = runTest {
    executor.script = { flowOf(AgentEvent.TextDelta("ок"), AgentEvent.Completed(finished("ок"))) }
    manager().launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals("ок", persistence.rows.last().content)
  }

  @Test
  fun `новый ход в том же диалоге отменяет предыдущий`() = runTest {
    executor.script = { call ->
      if (call.message == "первый") flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() }
      else flowOf(AgentEvent.Completed(finished("второй ответ")))
    }
    val m = manager()

    m.launchTurn("c1", "первый", AgentMode.CHAT)
    advanceUntilIdle()
    m.launchTurn("c1", "второй", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(1, executor.cancelledCollections)
    assertEquals("второй ответ", persistence.rows.last().content)
  }

  @Test
  fun `два диалога крутятся параллельно и оба дописывают`() = runTest {
    executor.script = { call ->
      if (call.convId == "a") flow { emit(AgentEvent.TextDelta("a...")); awaitCancellation() }
      else flowOf(AgentEvent.Completed(finished("ответ b")))
    }
    val m = manager()

    m.launchTurn("a", "вопрос a", AgentMode.CHAT)
    m.launchTurn("b", "вопрос b", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(0, executor.cancelledCollections)
    assertTrue(m.sessions.value.getValue("a").status.value is TurnStatus.Running)
    assertTrue(m.sessions.value.getValue("b").status.value is TurnStatus.Completed)
    assertEquals("ответ b", persistence.rows.last().content)
  }

  @Test
  fun `cancel гасит ход и убирает сессию`() = runTest {
    executor.script = { flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() } }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()
    m.cancel("c1")
    advanceUntilIdle()

    assertEquals(1, executor.cancelledCollections)
    assertNull(m.sessions.value["c1"])
  }

  @Test
  fun `TextDelta копится, StateChanged STREAMING сбрасывает буфер между витками`() = runTest {
    executor.script = {
      flowOf(
        AgentEvent.StateChanged(AgentState.STREAMING),
        AgentEvent.TextDelta("преамбула витка 1"),
        AgentEvent.StateChanged(AgentState.EXECUTING_TOOL),
        AgentEvent.StateChanged(AgentState.STREAMING),
        AgentEvent.TextDelta("финальный текст"),
        AgentEvent.StateChanged(AgentState.FINISHED),
        AgentEvent.Completed(finished("финальный текст")),
      )
    }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals("финальный текст", m.sessions.value.getValue("c1").streamingText.value)
  }

  @Test
  fun `во время работы инструмента пузырь показывает прогресс, а не преамбулу`() = runTest {
    // Ход с инструментом: преамбула первого витка, затем EXECUTING_TOOL + ToolStarted, и застываем
    // на исполнении инструмента. Проверяем ровно то, что видит пользователь в этот момент.
    executor.script = {
      flow {
        emit(AgentEvent.StateChanged(AgentState.STREAMING))
        emit(AgentEvent.TextDelta("Сейчас посмотрю твои заметки"))
        emit(AgentEvent.StateChanged(AgentState.EXECUTING_TOOL))
        emit(AgentEvent.ToolStarted("search_files", emptyMap()))
        awaitCancellation()
      }
    }
    val m = manager()

    m.launchTurn("c1", "найди мои заметки", AgentMode.CHAT)
    advanceUntilIdle()

    // Регрессия: раньше в этот момент оставалась преамбула (а на следующем витке гасла в пустоту —
    // «ответ пишется, потом затирается»). Теперь на месте пузыря — прогресс с именем инструмента.
    val shown = m.sessions.value.getValue("c1").streamingText.value
    assertTrue("ожидали прогресс инструмента, а пузырь показывал: '$shown'", shown.contains("search_files"))
  }

  @Test
  fun `ToolCompleted добавляется в трейс сессии по ходу`() = runTest {
    val trace = ToolTrace(iteration = 1, toolName = "get_time", arguments = emptyMap(), result = com.aiopenpocket.agent.core.mcp.ToolResult.Success("12:00"))
    executor.script = {
      flowOf(
        AgentEvent.ToolCompleted("get_time", trace.result, 1, emptyMap()),
        AgentEvent.Completed(finished("сейчас 12:00", listOf(trace))),
      )
    }
    val m = manager()

    m.launchTurn("c1", "который час", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(listOf("get_time"), m.sessions.value.getValue("c1").toolTrace.value.map { it.toolName })
  }

  @Test
  fun `AgentEvent Error переводит сессию в Failed с canRetry`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("сеть упала")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Failed
    assertEquals("сеть упала", status.message)
    assertTrue(status.canRetry)
    assertEquals(listOf("user"), persistence.rows.map { it.role })
  }

  @Test
  fun `retry перезапускает ход без повторного addMessage`() = runTest {
    var failFirst = true
    executor.script = {
      if (failFirst) { failFirst = false; flowOf(AgentEvent.Error("таймаут")) }
      else flowOf(AgentEvent.Completed(finished("со второго раза")))
    }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()
    m.retry("c1")
    advanceUntilIdle()

    assertEquals(listOf("user" to "вопрос", "assistant" to "со второго раза"), persistence.rows.map { it.role to it.content })
    assertEquals(2, executor.calls.size)
    assertEquals("вопрос", executor.calls[1].message)
    assertTrue(m.sessions.value.getValue("c1").status.value is TurnStatus.Completed)
  }

  /**
   * Task 10 (structured-plan-engine-907066): TurnManager.approvePlan (magic-message «Одобряю план,
   * выполни его по шагам.» triggering a tool-using turn) is gone — approval is now a typed
   * PlanCommands.approve(planId, version) call that never touches TurnManager at all (see
   * ChatViewModel.approvePlanCommand / PlanApprovalTest). What remains TurnManager's job is that
   * retry replays the SAME planId/budget as the original call — no more special-casing (the old
   * `planApproved` forced-false-on-retry hack is gone along with the field itself, since approval is
   * no longer per-turn state at all).
   */
  @Test
  fun `retry повторяет тот же planId и budget, что и оригинальный ход`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("сеть упала")) }
    val m = manager()
    val budget = PlanRunBudget(maxLlmIterationsPerStep = 5)

    m.launchTurn("c1", "сделай X", AgentMode.GOAL, planId = "plan-42", budget = budget)
    advanceUntilIdle()
    m.retry("c1")
    advanceUntilIdle()

    assertEquals(2, executor.calls.size)
    assertTrue(executor.calls.all { it.mode == AgentMode.GOAL && it.planId == "plan-42" && it.budget == budget })
  }

  // --- Task 10 (structured-plan-engine-907066): PlanProposed/PlanProgressed/PlanBlocked ----------

  @Test
  fun `PlanProposed пишет сообщение о предложенном плане и завершает ход без ошибки`() = runTest {
    executor.script = { flowOf(AgentEvent.PlanProposed("plan-1", 2)) }
    val m = manager()

    m.launchTurn("c1", "сделай X", AgentMode.PLAN)
    advanceUntilIdle()

    val assistant = persistence.rows.single { it.role == "assistant" }
    assertTrue(assistant.content.contains("2")) // версия
    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertNull(status.error)
    assertTrue(!status.canRetry)
  }

  @Test
  fun `PlanProgressed COMPLETED пишет прогресс без ошибки`() = runTest {
    val result = com.aiopenpocket.agent.core.plan.execution.PlanRunResult(
      planId = "plan-1",
      outcome = com.aiopenpocket.agent.core.plan.model.PlanRunOutcome.COMPLETED,
      executedStepIds = listOf("step-1"),
      blockedReason = null,
    )
    executor.script = { flowOf(AgentEvent.PlanProgressed(result)) }
    val m = manager()

    m.launchTurn("c1", "продолжай", AgentMode.GOAL, planId = "plan-1")
    advanceUntilIdle()

    val assistant = persistence.rows.single { it.role == "assistant" }
    assertTrue(assistant.content.isNotBlank())
    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertNull(status.error)
    assertTrue(!status.canRetry)
  }

  @Test
  fun `PlanProgressed пишет ответ шага и его карточки до строки прогресса`() = runTest {
    // Раньше в чат шла только строка прогресса: сам ответ шага («меню на неделю») не сохранялся нигде.
    val output = com.aiopenpocket.agent.core.plan.execution.PlanStepOutput(
      stepId = "menu",
      title = "Составить меню",
      status = com.aiopenpocket.agent.core.plan.model.StepStatus.COMPLETED,
      text = "Понедельник: гречка",
      cardSpecs = listOf("card-1", "card-2"),
      toolTrace = emptyList(),
    )
    val result = com.aiopenpocket.agent.core.plan.execution.PlanRunResult(
      planId = "plan-1",
      outcome = com.aiopenpocket.agent.core.plan.model.PlanRunOutcome.PARTIAL,
      executedStepIds = listOf("menu"),
      blockedReason = "Продолжу следующим запуском.",
      stepOutputs = listOf(output),
    )
    executor.script = { flowOf(AgentEvent.PlanProgressed(result)) }
    val m = manager()

    m.launchTurn("c1", "продолжай", AgentMode.GOAL, planId = "plan-1")
    advanceUntilIdle()

    val assistant = persistence.rows.filter { it.role == "assistant" }
    assertEquals(3, assistant.size)
    assertTrue(assistant[0].content.contains("Составить меню") && assistant[0].content.contains("Понедельник: гречка"))
    assertEquals("card-1", assistant[0].cardSpec)
    assertEquals("" to "card-2", assistant[1].content to assistant[1].cardSpec)
    // Строка прогресса — по названию шага, без id.
    assertEquals("Шаг «Составить меню» выполнен. Продолжу следующим запуском.", assistant[2].content)
    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertEquals(assistant[2].id, status.insertedMessageId)
  }

  @Test
  fun `PlanBlocked пишет причину как обычное сообщение, без ошибки и без canRetry`() = runTest {
    executor.script = { flowOf(AgentEvent.PlanBlocked("plan-1", "Уточни бюджет поездки")) }
    val m = manager()

    m.launchTurn("c1", "сделай X", AgentMode.PLAN)
    advanceUntilIdle()

    assertEquals("Уточни бюджет поездки", persistence.rows.single { it.role == "assistant" }.content)
    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertNull(status.error)
    assertTrue(!status.canRetry)
  }

  @Test
  fun `пустой ответ даёт Completed с ошибкой и canRetry без вставки в БД`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished(""))) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertNull(status.insertedMessageId)
    assertEquals("Модель вернула пустой ответ — попробуй ещё раз.", status.error)
    assertTrue(status.canRetry)
    assertEquals(listOf("user"), persistence.rows.map { it.role })
  }

  @Test
  fun `render_card - первая карточка в сообщении, остальные отдельными`() = runTest {
    val card1 = ToolTrace(1, "render_card", emptyMap(), com.aiopenpocket.agent.core.mcp.ToolResult.Success("{\"type\":\"todo\"}"))
    val card2 = ToolTrace(1, "render_card", emptyMap(), com.aiopenpocket.agent.core.mcp.ToolResult.Success("{\"type\":\"table\"}"))
    executor.script = { flowOf(AgentEvent.Completed(finished("вот карточки", listOf(card1, card2)))) }
    val m = manager()

    m.launchTurn("c1", "покажи", AgentMode.CHAT)
    advanceUntilIdle()

    val assistant = persistence.rows.filter { it.role == "assistant" }
    assertEquals(2, assistant.size)
    assertEquals("{\"type\":\"todo\"}", assistant[0].cardSpec)
    assertEquals("" to "{\"type\":\"table\"}", assistant[1].content to assistant[1].cardSpec)
  }

  @Test
  fun `invalidateRetryFor гасит ретрай только для того самого сообщения`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("упало")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()
    val userMsgId = persistence.rows.first().id

    m.invalidateRetryFor("c1", "чужой-id")
    advanceUntilIdle()
    assertTrue((m.sessions.value.getValue("c1").status.value as TurnStatus.Failed).canRetry)

    m.invalidateRetryFor("c1", userMsgId)
    advanceUntilIdle()
    assertTrue(!(m.sessions.value.getValue("c1").status.value as TurnStatus.Failed).canRetry)
  }

  @Test
  fun `clearError убирает Failed-сессию`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("упало")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()
    m.clearError("c1")
    advanceUntilIdle()

    assertNull(m.sessions.value["c1"])
  }

  @Test
  fun `markSeen убирает чисто завершённую сессию, но не Running`() = runTest {
    executor.script = { flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    m.markSeen("c1")
    advanceUntilIdle()
    assertTrue(m.sessions.value.containsKey("c1")) // Running не трогаем

    executor.script = { flowOf(AgentEvent.Completed(finished("готово"))) }
    m.launchTurn("c1", "ещё вопрос", AgentMode.CHAT)
    advanceUntilIdle()
    m.markSeen("c1")
    advanceUntilIdle()
    assertNull(m.sessions.value["c1"]) // чистое Completed — убрали

    // Task 5 (structured-plan-engine-907066): «ждёт ли план решения» больше не поле TurnStatus.Completed
    // этой сессии (см. TurnManager.markSeen kdoc) — оно состояние PlanRepository. Чисто завершённая
    // план-фаза больше не держит сессию особняком от CHAT и тоже убирается markSeen.
    executor.script = { flowOf(AgentEvent.Completed(finished("1. план"))) }
    m.launchTurn("c1", "сделай X", AgentMode.PLAN)
    advanceUntilIdle()
    m.markSeen("c1")
    advanceUntilIdle()
    assertNull(m.sessions.value["c1"]) // чисто завершённая план-фаза тоже убирается
  }

  @Test
  fun `badges отдаёт статусы по всем диалогам`() = runTest {
    executor.script = { call ->
      if (call.convId == "a") flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() }
      else flowOf(AgentEvent.Completed(finished("ответ")))
    }
    val m = manager()

    m.launchTurn("a", "вопрос", AgentMode.CHAT)
    m.launchTurn("b", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    // badges — производный Flow: сессии уже наполнены, поэтому первая же эмиссия несёт оба статуса.
    val snapshot = m.badges.first()

    assertTrue(snapshot["a"] is TurnStatus.Running)
    assertTrue(snapshot["b"] is TurnStatus.Completed)
  }

  @Test
  fun `уведомление уходит только когда приложение в фоне`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("готово"))) }
    val m = manager()

    inForeground = true
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()
    assertEquals(0, notifier.shown.size)

    inForeground = false
    m.launchTurn("c2", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(1, notifier.shown.size)
    val shown = notifier.shown.single()
    assertEquals("c2", shown.convId)
    assertEquals("Чат c2", shown.title)
    assertEquals("готово", shown.preview)
    assertTrue(!shown.failed)
  }

  @Test
  fun `падение хода в фоне тоже уведомляет`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("сеть упала")) }
    inForeground = false
    manager().launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    val shown = notifier.shown.single()
    assertTrue(shown.failed)
    assertEquals("сеть упала", shown.preview)
  }

  // --- Защита хода на время исполнения (spec 2026-07-28-turn-foreground-service-design) ---------

  @Test
  fun `ход берёт защиту перед стартом и отпускает после завершения`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("ок"))) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(listOf("c1"), lifeSupport.acquired)
    assertEquals(listOf("c1"), lifeSupport.released)
  }

  @Test
  fun `упавший ход тоже отпускает защиту`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("бек лёг")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(listOf("c1"), lifeSupport.released)
  }

  @Test
  fun `отменённый ход отпускает защиту`() = runTest {
    executor.script = { flow { awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    m.cancel("c1")
    advanceUntilIdle()

    assertEquals(listOf("c1"), lifeSupport.released)
  }

  @Test
  fun `отказ в защите не мешает ходу доехать и не порождает release`() = runTest {
    lifeSupport.grant = false
    executor.script = { flowOf(AgentEvent.Completed(finished("ок"))) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals("ок", persistence.rows.last().content)
    assertTrue("release без успешного acquire увёл бы рефкаунт в минус", lifeSupport.released.isEmpty())
  }

  @Test
  fun `истечение защиты в фоне валит ход с возможностью повторить`() = runTest {
    inForeground = false
    executor.script = { flow { awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    lifeSupport.loseProtection(setOf("c1"))
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Failed
    assertEquals(TurnTexts.PROTECTION_EXPIRED, status.message)
    assertTrue("реплика юзера в истории есть — «Повторить» обязано быть доступно", status.canRetry)
  }

  @Test
  fun `истечение защиты не перетирает уже завершённый ход`() = runTest {
    inForeground = false
    executor.script = { flowOf(AgentEvent.Completed(finished("успел"))) }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    lifeSupport.loseProtection(setOf("c1"))
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertNull("ответ доехал — ошибке тут взяться неоткуда", status.error)
    assertEquals("успел", persistence.rows.last().content)
  }

  @Test
  fun `cancelAndAwait дожидается остановки хода и забывает сессию`() = runTest {
    executor.script = { flow { awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    m.cancelAndAwait("c1")

    assertNull("сессия должна быть забыта до возврата из cancelAndAwait", m.sessions.value["c1"])
    assertEquals(
      "ход обязан быть фактически остановлен: следом вызывающий сносит сам диалог",
      1,
      executor.cancelledCollections,
    )
  }

  @Test
  fun `истечение защиты при открытом экране ход не трогает`() = runTest {
    inForeground = true
    executor.script = { flow { awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    lifeSupport.loseProtection(setOf("c1"))
    advanceUntilIdle()

    assertTrue(
      "foreground-процесс система не вытесняет — резать длинный ход на глазах у юзера незачем",
      m.sessions.value.getValue("c1").status.value is TurnStatus.Running,
    )
  }

  companion object {
    fun finished(text: String, trace: List<ToolTrace> = emptyList()) = AgentResult(
      text = text,
      toolTrace = trace,
      state = AgentState.FINISHED,
      iterations = 1,
      promptTokensEstimate = 100,
      completionTokensEstimate = 10,
    )
  }
}
