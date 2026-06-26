package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.loop.AgentMode

sealed class ChatIntent {
  data class UpdateInput(val text: String) : ChatIntent()
  data object SendMessage : ChatIntent()
  data object CancelRequest : ChatIntent()
  data object ToggleToolTrace : ChatIntent()
  data class TogglePin(val messageId: String) : ChatIntent()
  data object RetryRequest : ChatIntent()
  data object ClearError : ChatIntent()
  data object NavigateBack : ChatIntent()
  data object ToggleVoiceInput : ChatIntent()
  data object ClearVoiceError : ChatIntent()

  /** Сменить режим агента (Чат/План/Цель). Заменяет прежний boolean-тогл план-мода. */
  data class SetMode(val mode: AgentMode) : ChatIntent()
  /** План-мод: одобрить план → перейти к выполнению с инструментами. */
  data object ApprovePlan : ChatIntent()
  /** План-мод: вернуться к правке (снять бар одобрения, дать дописать). */
  data object EditPlan : ChatIntent()

  /** Урок: показать следующий шаг (или завершить, если шаг был последним). */
  data object AdvanceLesson : ChatIntent()
  /** Урок: квиз пройден — показать карточку завершения. */
  data object CompleteLessonQuiz : ChatIntent()
}
