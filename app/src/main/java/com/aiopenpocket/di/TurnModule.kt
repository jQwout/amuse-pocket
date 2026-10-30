package com.aiopenpocket.di

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53
// @trace approval-outcome-honesty-554a8c

import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentLoopOrchestrator
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.agent.loop.PendingApprovals
import com.aiopenpocket.agent.loop.ReflectionOrchestrator
import com.aiopenpocket.agent.loop.StructuredPlanOrchestrator
import com.aiopenpocket.agent.loop.TurnExecutor
import com.aiopenpocket.agent.loop.TurnLifeSupport
import com.aiopenpocket.agent.core.llm.ReasoningEffortContext
import com.aiopenpocket.agent.core.llm.ModelKeyGate
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.reasoningEffortFor
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.agent.loop.TurnPersistence
import com.aiopenpocket.ui.conversations.displayTitle
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOn

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
      // The dispatch point by mode (not in ChatViewModel, as on the old branch): the manager has already
      // passed the mode. REFLECT is a 3-phase orchestrator on top of the same ReactLoopRunner. PLAN/GOAL
      // (Task 10, structured-plan-engine-907066) are the typed Structured Plan Engine (PlanPlanner →
      // PlanRunner → PlanExecutor/PlanVerifier), not the old prompt-only gate/loop that used to live in
      // AgentLoopOrchestrator: Task 15 (structured-plan-engine-907066) removed them together with the
      // planApproved/goalMaxIterations params and the prompts/modes/*.md assets — from this dispatch
      // point they were unreachable anyway. CHAT/TUTOR — as before, the main loop; this branch needs no
      // planId/budget (AgentLoopOrchestrator.execute does not take them at all).
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
        // How hard the model thinks is decided HERE, once per turn, by mode (lesson answer: low; chat: medium;
        // planning and critique: high) and travels in the coroutine context down to OpenAiCompatClient.
        // flowOn with a non-dispatcher element adds no thread hop and no buffer.
      }.flowOn(ReasoningEffortContext(reasoningEffortFor(mode)))
    }

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnPersistence(repository: ConversationRepository): TurnPersistence = object : TurnPersistence {
    override suspend fun addUserMessage(conversationId: String, text: String): String =
      repository.addMessage(conversationId, "user", text)

    override suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String =
      repository.addMessage(conversationId, "assistant", text, cardSpec = cardSpec)

    // The title goes into a push notification, so it is the display title: a lesson chat is stored under the
    // lesson id (how_model_sees) and the notification must name the lesson instead.
    override suspend fun conversationTitle(conversationId: String): String? =
      repository.getConversation(conversationId)?.displayTitle()
  }

  // The only reader is TurnManager's "needs permission" push: it must know whether the request it saw
  // is still waiting before it nudges the user about it.
  @Provides
  @SingleIn(AppScope::class)
  fun providePendingApprovals(coordinator: ToolApprovalCoordinator): PendingApprovals =
    PendingApprovals { requestId -> coordinator.isPending(requestId) }

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnManager(
    executor: TurnExecutor,
    persistence: TurnPersistence,
    foreground: ForegroundState,
    notifier: TurnNotifier,
    pendingApprovals: PendingApprovals,
    lifeSupport: TurnLifeSupport,
    selectedProvider: SelectedLlmProvider,
    modelKeyGate: ModelKeyGate,
    log: com.aiopenpocket.agent.core.AgentLog,
  ): TurnManager = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = foreground,
    notifier = notifier,
    pendingApprovals = pendingApprovals,
    lifeSupport = lifeSupport,
    // An app-level scope of its own: an agent turn lives as long as the process does, not the screen.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    monotonicNowMs = android.os.SystemClock::uptimeMillis,
    // Lambdas rather than values: both are read on every turn, so a key entered in Settings is picked up
    // at once, without rebuilding the DI graph.
    selectedProvider = { selectedProvider() },
    hasModelKey = { modelKeyGate() },
    log = log,
  )
}
