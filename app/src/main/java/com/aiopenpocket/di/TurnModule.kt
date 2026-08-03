package com.aiopenpocket.di

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentLoopOrchestrator
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.agent.loop.ReflectionOrchestrator
import com.aiopenpocket.agent.loop.StructuredPlanOrchestrator
import com.aiopenpocket.agent.loop.TurnExecutor
import com.aiopenpocket.agent.loop.TurnLifeSupport
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.agent.loop.TurnPersistence
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@ContributesTo(AppScope::class)
@BindingContainer
object TurnModule {

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnExecutor(
    orchestrator: AgentLoopOrchestrator,
    reflectionOrchestrator: ReflectionOrchestrator,
    structuredPlanOrchestrator: StructuredPlanOrchestrator,
  ): TurnExecutor =
    TurnExecutor { conversationId, message, provider, mode, planId, budget, systemPromptExtra, executionMode ->
      // Точка диспатча по режиму (а не в ChatViewModel, как на старой ветке): менеджер уже прокинул
      // mode. REFLECT — 3-фазный оркестратор поверх того же ReactLoopRunner. PLAN/GOAL (Task 10,
      // structured-plan-engine-907066) — типовой Structured Plan Engine (PlanPlanner → PlanRunner →
      // PlanExecutor/PlanVerifier), а не старый prompt-only гейт/петля, которые раньше жили в
      // AgentLoopOrchestrator: Task 15 (structured-plan-engine-907066) убрал их вместе с
      // planApproved/goalMaxIterations params и ассетами prompts/modes/*.md — с этой точки диспатча
      // они и так были недостижимы. CHAT/TUTOR — как раньше, основной цикл; planId/budget этой ветке
      // не нужны (AgentLoopOrchestrator.execute их не принимает вовсе).
      when (mode) {
        AgentMode.REFLECT -> reflectionOrchestrator.execute(conversationId, message, provider, executionMode)
        // PLAN's propose() only ever sees internal control schemas (never action tools), so PREVIEW
        // vs LIVE is a no-op there; GOAL actually executes step tools, so executionMode must reach it.
        AgentMode.PLAN -> structuredPlanOrchestrator.propose(conversationId, message, provider)
        AgentMode.GOAL -> structuredPlanOrchestrator.executeGoal(conversationId, planId, message, provider, budget, executionMode)
        else -> orchestrator.execute(
          conversationId, message, provider, mode,
          systemPromptExtra = systemPromptExtra, executionMode = executionMode,
        )
      }
    }

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnPersistence(repository: ConversationRepository): TurnPersistence = object : TurnPersistence {
    override suspend fun addUserMessage(conversationId: String, text: String): String =
      repository.addMessage(conversationId, "user", text)

    override suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String =
      repository.addMessage(conversationId, "assistant", text, cardSpec = cardSpec)

    override suspend fun conversationTitle(conversationId: String): String? =
      repository.getConversation(conversationId)?.title
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnManager(
    executor: TurnExecutor,
    persistence: TurnPersistence,
    foreground: ForegroundState,
    notifier: TurnNotifier,
    lifeSupport: TurnLifeSupport,
    selectedProvider: SelectedLlmProvider,
    log: com.aiopenpocket.agent.core.AgentLog,
  ): TurnManager = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = foreground,
    notifier = notifier,
    lifeSupport = lifeSupport,
    // Свой скоуп уровня приложения: ход агента живёт, пока жив процесс, а не экран.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    monotonicNowMs = android.os.SystemClock::uptimeMillis,
    // Лямбда, а не значение: провайдер читается на каждом ходе, поэтому переключение в Настройках
    // подхватывается сразу, без пересборки DI-графа.
    selectedProvider = { selectedProvider() },
    log = log,
  )
}
