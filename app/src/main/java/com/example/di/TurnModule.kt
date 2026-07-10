package com.aiopenpocket.di

// @trace fonovye-hody-agenta-dde42d

import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentLoopOrchestrator
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.agent.loop.ReflectionOrchestrator
import com.aiopenpocket.agent.loop.TurnExecutor
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
  ): TurnExecutor =
    TurnExecutor { conversationId, message, provider, mode, planApproved, goalMaxIterations ->
      // Режим REFLECT — отдельный 3-фазный оркестратор (черновик → критика → итог) поверх того же
      // ReactLoopRunner; остальные режимы идут через основной цикл AgentLoopOrchestrator. Точку
      // диспатча держим здесь (а не в ChatViewModel, как на старой ветке): менеджер уже прокинул mode.
      if (mode == AgentMode.REFLECT) {
        reflectionOrchestrator.execute(conversationId, message, provider)
      } else {
        orchestrator.execute(conversationId, message, provider, mode, planApproved, goalMaxIterations)
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
  ): TurnManager = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = foreground,
    notifier = notifier,
    // Свой скоуп уровня приложения: ход агента живёт, пока жив процесс, а не экран.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    monotonicNowMs = android.os.SystemClock::uptimeMillis,
  )
}
