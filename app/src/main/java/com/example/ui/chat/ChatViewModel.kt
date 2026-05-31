package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.ChatMessage
import com.example.data.network.GeminiContent
import com.example.data.network.GeminiPart
import com.example.data.repository.ChatRepository
import com.example.data.network.GeminiClient
import com.example.navigation.NavigationBridge
import com.example.navigation.NavigationEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val client: GeminiClient,
    private val navigationBridge: NavigationBridge
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state = _state.asStateFlow()

    private var activeLlmJob: Job? = null

    init {
        // Collect messages reactive from SQLite
        viewModelScope.launch {
            repository.allMessages.collect { list ->
                _state.update { it.copy(messages = list) }
            }
        }

        // Collect cookies info if any
        viewModelScope.launch {
            repository.getCookieFlow().collect { userCookie ->
                _state.update { it.copy(capturedCookie = userCookie?.cookieValue) }
            }
        }
    }

    fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.UpdateInput -> {
                _state.update { it.copy(inputText = intent.text) }
            }
            is ChatIntent.ClearInput -> {
                _state.update { it.copy(inputText = "") }
            }
            is ChatIntent.SendMessage -> {
                val input = _state.value.inputText.trim()
                val currentAttachments = _state.value.attachments
                if (input.isEmpty() && currentAttachments.isEmpty()) return

                // Construct attachments description if any
                val attachmentDesc = if (currentAttachments.isNotEmpty()) {
                    currentAttachments.joinToString(prefix = " [Добавлено: ", postfix = "]") { "${it.name} (${it.mimeType})" }
                } else ""

                val textToSend = "$input$attachmentDesc"

                // 1. Insert user message to SQLite
                viewModelScope.launch {
                    repository.insertMessage(
                        ChatMessage(
                            sender = "user",
                            content = textToSend,
                            attachmentName = currentAttachments.firstOrNull()?.name,
                            attachmentMimeType = currentAttachments.firstOrNull()?.mimeType
                        )
                    )
                }

                // Clean typing input buffer and active attachments immediately
                _state.update { it.copy(inputText = "", attachments = emptyList(), isLoading = true, error = null) }

                // 2. Launch cancelable prompt chain Job
                activeLlmJob = viewModelScope.launch {
                    try {
                        // Gather history from our current messages list
                        val requestHistory = mutableListOf<GeminiContent>()
                        // Limit to last 20 messages to fit token constraints comfortably
                        val lastMessages = _state.value.messages.takeLast(20)
                        lastMessages.forEach { msg ->
                            val role = if (msg.sender == "user") "user" else "model"
                            requestHistory.add(
                                GeminiContent(
                                    role = role,
                                    parts = listOf(GeminiPart(text = msg.content))
                                )
                            )
                        }

                        // Append active new message if history was collected before save
                        if (requestHistory.isEmpty() || requestHistory.last().parts.firstOrNull()?.text != textToSend) {
                            requestHistory.add(
                                GeminiContent(
                                    role = "user",
                                    parts = listOf(GeminiPart(text = textToSend))
                                )
                            )
                        }

                        val aiResponse = client.generateResponse(requestHistory)

                        // 3. Save AI response to SQLite
                        repository.insertMessage(
                            ChatMessage(
                                sender = "model",
                                content = aiResponse
                            )
                        )
                    } catch (e: Exception) {
                        repository.insertMessage(
                            ChatMessage(
                                sender = "system",
                                content = "Системная ошибка: ${e.message ?: "Не удалось загрузить ответ."}"
                            )
                        )
                    } finally {
                        _state.update { it.copy(isLoading = false) }
                    }
                }
            }
            is ChatIntent.CancelRequest -> {
                // Cancel active job to stop the entire request chain immediately
                activeLlmJob?.cancel()
                activeLlmJob = null
                _state.update { it.copy(isLoading = false) }

                // Save system message to SQLite to visually notify user that request was aborted
                viewModelScope.launch {
                    repository.insertMessage(
                        ChatMessage(
                            sender = "system",
                            content = "Запрос к LLM был отменен пользователем."
                        )
                    )
                }
            }
            is ChatIntent.AttachMockFile -> {
                val newAttachment = AttachmentInfo(
                    name = intent.name,
                    mimeType = intent.mimeType
                )
                _state.update {
                    it.copy(attachments = it.attachments + newAttachment)
                }
            }
            is ChatIntent.RemoveAttachment -> {
                _state.update {
                    val list = it.attachments.toMutableList()
                    if (intent.index in list.indices) {
                        list.removeAt(intent.index)
                    }
                    it.copy(attachments = list)
                }
            }
            is ChatIntent.ClearHistory -> {
                viewModelScope.launch {
                    repository.clearMessages()
                }
            }
            is ChatIntent.Logout -> {
                viewModelScope.launch {
                    activeLlmJob?.cancel()
                    repository.clearCookie()
                    _state.update { ChatUiState() }
                    navigationBridge.navigateTo(NavigationEvent.NavigateToWebView)
                }
            }
        }
    }
}
