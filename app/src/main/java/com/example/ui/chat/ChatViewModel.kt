package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.database.ChatMessage
import com.example.data.network.NvidiaMessage
import com.example.data.repository.ChatRepository
import com.example.data.network.NvidiaClient
import com.example.navigation.NavigationBridge
import com.example.navigation.NavigationEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatViewModel(
    private val repository: ChatRepository,
    private val client: NvidiaClient,
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

        // Load initial saved custom API Key and Persona Selection
        viewModelScope.launch {
            val key = repository.getCustomApiKey()
            val personaId = repository.getPersonaId()
            _state.update { it.copy(customApiKey = key, selectedPersonaId = personaId) }
        }
    }

    private fun loadPersonaContent(personaId: String): String {
        return try {
            val context = com.example.di.Metro.context
            context.assets.open("personas/$personaId.md").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Характер: Деловой напарник по умолчанию."
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
                        val requestHistory = mutableListOf<NvidiaMessage>()
                        // Limit to last 20 messages to fit token constraints comfortably
                        val lastMessages = _state.value.messages.takeLast(20)
                        lastMessages.forEach { msg ->
                            val role = if (msg.sender == "user") "user" else "assistant"
                            requestHistory.add(
                                NvidiaMessage(
                                    role = role,
                                    content = msg.content
                                )
                            )
                        }

                        // Append active new message if history was collected before save
                        if (requestHistory.isEmpty() || requestHistory.last().content != textToSend) {
                            requestHistory.add(
                                NvidiaMessage(
                                    role = "user",
                                    content = textToSend
                                )
                            )
                        }

                        val personaId = _state.value.selectedPersonaId
                        val personaContent = loadPersonaContent(personaId)
                        val systemPrompt = """
Ты — локальный AI-агент на Android-устройстве пользователя.
Если твоя задача подразумевает визуальный результат (список дел, график, таймер, погода, таблица, форма), ты ОБЯЗАН вернуть ровно один валидный JSON-блок, завернутый в шаблон render_card. Не пиши никакого лишнего текста до или после JSON блока, выдавай только JSON.
Шаблоны JSON для render_card:

1. todo_list:
{
  "template": "todo_list",
  "params": {
    "title": "Список задач",
    "items": [{"item_text": "Купить молоко", "item_done": false}]
  }
}

2. weather_card:
{
  "template": "weather_card",
  "params": {
    "city": "Москва",
    "days": [{"day": "Пн", "temp": "+15°C", "text": "Ясно", "icon": "sunny"}]
  }
}

3. timer:
{
  "template": "timer",
  "params": {
    "label": "Таймер фокуса",
    "duration_sec": 120
  }
}

4. chart_bar:
{
  "template": "chart_bar",
  "params": {
    "title": "Расходы за весну",
    "labels": ["Март", "Апрель", "Май"],
    "values": [3200.0, 4100.0, 2500.0]
  }
}

5. table:
{
  "template": "table",
  "params": {
    "headers": ["Продукт", "Цена", "Наличие"],
    "rows": [["Апельсины", "120₽", "Есть"], ["Манго", "350₽", "Под заказ"]]
  }
}

6. form:
{
  "template": "form",
  "params": {
    "submit_label": "Отправить",
    "fields": [
      {"name": "userName", "label": "Имя", "placeholder": "Введите имя", "type": "text"},
      {"name": "userAge", "label": "Возраст", "placeholder": "Введите возраст", "type": "number"}
    ]
  }
}

Если тебе не требуется возвращать визуальную карточку, отвечай обычным текстом в соответствии со своим характером. Не придумывай новые шаблоны.

---
ТВОЙ ТЕКУЩИЙ ХАРАКТЕР И ПЕРСОНА (выбрана пользователем):
${personaContent}
                        """.trimIndent()

                        val aiResponse = client.generateResponse(
                            history = requestHistory,
                            userCustomApiKey = _state.value.customApiKey,
                            systemPrompt = systemPrompt
                        )

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
            is ChatIntent.SaveApiKey -> {
                viewModelScope.launch {
                    repository.saveCustomApiKey(intent.apiKey)
                    _state.update { it.copy(customApiKey = intent.apiKey) }
                }
            }
            is ChatIntent.ChangePersona -> {
                viewModelScope.launch {
                    repository.savePersonaId(intent.personaId)
                    _state.update { it.copy(selectedPersonaId = intent.personaId) }
                }
            }
        }
    }
}
