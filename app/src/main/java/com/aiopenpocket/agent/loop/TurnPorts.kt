package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.flow.Flow

/**
 * Порт исполнения хода — узкий шов между TurnManager и AgentLoopOrchestrator/ReflectionOrchestrator/
 * StructuredPlanOrchestrator, чтобы менеджер тестировался скриптовым фейком без LLM/Room.
 *
 * Task 10 (structured-plan-engine-907066): `planApproved`/`goalMaxIterations` убраны — approval
 * больше не per-turn флаг (см. PlanCommands), а [AgentMode.GOAL] бюджет теперь многомерный
 * [PlanRunBudget], а не один `goalMaxIterations`. [planId] — какой план исполняет GOAL (null =
 * StructuredPlanOrchestrator сам резолвит план диалога); [budget] используется только PLAN/GOAL-веткой
 * диспатча в TurnModule, CHAT/TUTOR/REFLECT его не читают.
 */
fun interface TurnExecutor {
  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider,
    mode: AgentMode,
    planId: String?,
    budget: PlanRunBudget,
    /** Доверенная добавка к системному промпту для этого хода (напр. контекст текущего шага урока). */
    systemPromptExtra: String?,
    /** LIVE (по умолчанию у вызывающего TurnManager) или PREVIEW — см. ExecutionPolicy. */
    executionMode: ExecutionMode,
  ): Flow<AgentEvent>
}

/** То немногое из ConversationRepository, что нужно ходу: записать реплики и узнать заголовок. */
interface TurnPersistence {
  suspend fun addUserMessage(conversationId: String, text: String): String
  suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String
  suspend fun conversationTitle(conversationId: String): String?
}

/** Приложение сейчас на экране? Гейт для пуша об окончании фонового хода. */
fun interface ForegroundState {
  fun isInForeground(): Boolean
}

/** Показ уведомления «ход завершился, пока приложение в фоне». */
fun interface TurnNotifier {
  fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean)

  /**
   * Агенту нужно разрешение, а приложение свёрнуто. Удаление и отправка наружу всегда ждут «Разрешить»,
   * и без пуша запрос молча истёк бы отказом через две минуты. По умолчанию — тот же пуш хода,
   * ведущий в чат: там и висит полоса подтверждения.
   */
  fun approvalNeeded(conversationId: String, conversationTitle: String?, action: String) =
    turnFinished(conversationId, conversationTitle, tr("Агенту нужно разрешение: $action. Открой чат, чтобы ответить.", "The agent needs your permission: $action. Open the chat to answer."), false)
}

/**
 * Защита хода от вытеснения процесса, пока приложение в фоне (спека
 * `docs/superpowers/specs/2026-07-28-turn-foreground-service-design.md`). Отдельный порт, а не
 * вызов Android напрямую, по той же причине, что [ForegroundState]/[TurnNotifier]: [TurnManager]
 * обязан тестироваться без Android.
 */
interface TurnLifeSupport {
  /**
   * Просит защиту для [conversationId]. `false` — система отказала; типичный случай — старт хода,
   * когда приложение уже в фоне (с API 31 стартовать foreground-сервис оттуда нельзя). Ход при
   * этом всё равно исполняется, просто без гарантии — ровно как до появления этой фичи.
   */
  suspend fun acquire(conversationId: String): Boolean

  /** Идемпотентно снимает защиту. Вызывать ТОЛЬКО если [acquire] вернул `true`. */
  fun release(conversationId: String)

  /**
   * Регистрирует реакцию на «система отобрала защиту» (таймаут `shortService`). Аргумент —
   * conversationId, которые на этот момент были защищены. Вызывается один раз, при сборке графа.
   */
  fun onProtectionLost(handler: (Set<String>) -> Unit)
}
