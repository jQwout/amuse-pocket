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
    val error: String? = null,
    val customApiKey: String = "",
    val selectedPersonaId: String = "sharp_partner",
    val personaList: List<String> = listOf(
        "silent_butler",
        "attentive_secretary",
        "sharp_partner",
        "egalitarian_partner",
        "friendly_companion",
        "ironic_assistant",
        "strict_coach"
    )
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
    data class SaveApiKey(val apiKey: String) : ChatIntent
    data class ChangePersona(val personaId: String) : ChatIntent
}
