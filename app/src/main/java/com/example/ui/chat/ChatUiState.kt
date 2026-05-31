package com.example.ui.chat

import com.example.data.database.ChatMessage

data class AttachmentInfo(
    val name: String,
    val mimeType: String,
    val previewUrl: String? = null
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val attachments: List<AttachmentInfo> = emptyList(),
    val capturedCookie: String? = null,
    val error: String? = null
)

sealed interface ChatIntent {
    data class UpdateInput(val text: String) : ChatIntent
    object ClearInput : ChatIntent
    object SendMessage : ChatIntent
    object CancelRequest : ChatIntent
    data class AttachMockFile(val name: String, val mimeType: String) : ChatIntent
    data class RemoveAttachment(val index: Int) : ChatIntent
    object ClearHistory : ChatIntent
    object Logout : ChatIntent
}
