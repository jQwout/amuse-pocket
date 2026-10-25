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
   * запускает исполнение в фоне: PlanScheduler ставит typed heartbeat на следующий шаг (без
   * расписания — сразу), и ответ шага ложится в этот же чат.
   */
  data class ApprovePlan(val planId: String, val version: Int) : ChatIntent()
  /**
   * Запрос правок: план уходит в DRAFT, и сразу запускается ход PLAN с комментарием пользователя
   * (или «Пересобери план с учётом правок») — иначе карточка исчезала, а новой версии никто не ждал.
   */
  data class RequestPlanChanges(val planId: String, val version: Int, val feedback: String) : ChatIntent()
  /** Task 5: приостановить ACTIVE-план (и его typed heartbeat/schedule — см. PlanScheduleSync). */
  data class PausePlan(val planId: String) : ChatIntent()
  /** Task 5: возобновить PAUSED-план. */
  data class ResumePlan(val planId: String) : ChatIntent()
  /** Task 5: отменить план — терминальный статус. */
  data class CancelPlan(val planId: String) : ChatIntent()

  /**
   * USER_CONFIRMATION: пользователь подтверждает шаг, заблокированный на критерии-подтверждении
   * (design fork «только пере-проверить, без ре-рана»). Идёт в `PlanCommands.confirmStep`: пишет
   * evidence подтверждения и ПЕРЕ-проверяет критерии БЕЗ повторного запуска шага (COMPLETED, если
   * закрыт последний обязательный критерий; иначе шаг просто разблокируется в READY).
   */
  data class ConfirmPlanStep(val planId: String, val stepId: String) : ChatIntent()

  /**
   * План-мод: «Поправить» — чисто локальный UI-жест (прячет approval-бар в ChatUiState, даёт
   * дописать в чат), в отличие от [RequestPlanChanges] не обращается к PlanRepository.
   */
  data object EditPlan : ChatIntent()

  /** Урок: показать следующий шаг (или завершить, если шаг был последним). */
  data object AdvanceLesson : ChatIntent()
  /** Урок: квиз пройден — показать карточку завершения. */
  data object CompleteLessonQuiz : ChatIntent()

  /** «Нарисуй под меня» на шаге-картинке урока: перерисовать [imagePrompt] под профиль и язык. */
  data class PersonalizeLessonImage(val imagePrompt: String, val caption: String) : ChatIntent()

  /** Шаг урока "video": пользователь снял или выбрал ролик [video] — отдать его Muse Spark с [videoPrompt]. */
  data class AnalyzeLessonVideo(val videoPrompt: String, val video: android.net.Uri) : ChatIntent()

  /** Урок: свободный вопрос к LLM прямо во время урока (кнопка «Спросить»). */
  data class AskDuringLesson(val text: String) : ChatIntent()

  /**
   * Tool-approval бар (Task 9): пользователь разрешил/отклонил `state.pendingToolApproval`. Оба
   * параметров не несут — резолвят ровно тот запрос, что сейчас лежит в state (см. ChatViewModel).
   */
  data object ApproveToolCall : ChatIntent()
  data object DenyToolCall : ChatIntent()
}
