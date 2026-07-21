package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.loop.AgentMode

sealed class ChatIntent {
  data class UpdateInput(val text: String) : ChatIntent()
  data object SendMessage : ChatIntent()
  data object CancelRequest : ChatIntent()
  data object ToggleToolTrace : ChatIntent()
  /** Удалить сообщение (подтверждение — на стороне UI, до отправки этого интента). */
  data class DeleteMessage(val messageId: String) : ChatIntent()
  data object RetryRequest : ChatIntent()
  data object ClearError : ChatIntent()
  data object NavigateBack : ChatIntent()
  data object ToggleVoiceInput : ChatIntent()
  /** Отмена записи: сбросить распознавание без вставки текста (кнопка-корзина в баре записи). */
  data object CancelVoiceInput : ChatIntent()
  data object ClearVoiceError : ChatIntent()

  /** Сменить режим агента (Чат/План/Цель). Заменяет прежний boolean-тогл план-мода. */
  data class SetMode(val mode: AgentMode) : ChatIntent()

  /**
   * Task 5 (structured-plan-engine-907066): typed plan commands — идут напрямую в
   * `PlanCommands`/`PlanRepository`, БЕЗ magic chat message («Одобряю план, выполни его по
   * шагам» больше нигде не пишется в историю — см. удалённый `TurnTexts.APPROVE_PLAN_MESSAGE`).
   * [version] — ровно та версия, которую пользователь видел в PlanApprovalBar/PlanCard:
   * `PlanCommands` отклоняет команду над устаревшей (уже не текущей pending) версией. Одобрение
   * само по себе исполнение НЕ запускает (design spec §9) — это отдельное действие поверх GOAL.
   */
  data class ApprovePlan(val planId: String, val version: Int) : ChatIntent()
  /** Task 5: запрос правок с комментарием для планировщика — план уходит в DRAFT, ждёт новый proposal. */
  data class RequestPlanChanges(val planId: String, val version: Int, val feedback: String) : ChatIntent()
  /** Task 5: приостановить ACTIVE-план (и его typed heartbeat/schedule — см. PlanScheduleSync). */
  data class PausePlan(val planId: String) : ChatIntent()
  /** Task 5: возобновить PAUSED-план. */
  data class ResumePlan(val planId: String) : ChatIntent()
  /** Task 5: отменить план — терминальный статус. */
  data class CancelPlan(val planId: String) : ChatIntent()

  /**
   * План-мод: «Поправить» — чисто локальный UI-жест (прячет approval-бар в ChatUiState, даёт
   * дописать в чат), в отличие от [RequestPlanChanges] не обращается к PlanRepository.
   */
  data object EditPlan : ChatIntent()

  /** Урок: показать следующий шаг (или завершить, если шаг был последним). */
  data object AdvanceLesson : ChatIntent()
  /** Урок: квиз пройден — показать карточку завершения. */
  data object CompleteLessonQuiz : ChatIntent()

  /** Урок: свободный вопрос к LLM прямо во время урока (кнопка «Спросить»). */
  data class AskDuringLesson(val text: String) : ChatIntent()

  /**
   * Tool-approval бар (Task 9): пользователь разрешил/отклонил `state.pendingToolApproval`. Оба
   * параметров не несут — резолвят ровно тот запрос, что сейчас лежит в state (см. ChatViewModel).
   */
  data object ApproveToolCall : ChatIntent()
  data object DenyToolCall : ChatIntent()
}
