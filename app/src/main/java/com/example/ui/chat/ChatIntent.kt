package com.aiopenpocket.ui.chat

sealed class ChatIntent {
  data class UpdateInput(val text: String) : ChatIntent()
  data object SendMessage : ChatIntent()
  data object CancelRequest : ChatIntent()
  data object ToggleToolTrace : ChatIntent()
  data class TogglePin(val messageId: String) : ChatIntent()
  data object ClearError : ChatIntent()
  data object RetryRequest : ChatIntent()
  data object NavigateBack : ChatIntent()
  data object TogglePlanMode : ChatIntent()
}
