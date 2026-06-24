package com.aiopenpocket.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentEvent
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.AgentLoopOrchestrator
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ChatViewModel::class)
class ChatViewModel(
  private val conversationRepository: ConversationRepository,
  private val agentLoop: AgentLoopOrchestrator,
  private val settingsRepository: SettingsRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(ChatUiState())
  val state: StateFlow<ChatUiState> = _state.asStateFlow()

  private var activeJob: Job? = null
  private var messagesJob: Job? = null
  private var responseStartTime: Long = 0L

  fun setConversationId(id: String) {
    if (_state.value.conversationId == id) return
    activeJob?.cancel()
    messagesJob?.cancel()
    _state.update { it.copy(conversationId = id, messages = emptyList(), streamingText = "", toolTrace = emptyList(), isLoading = false, isTutorial = false, levelId = null) }
    // Новый чат (id пуст): диалога ещё нет, его создаст ensureConversation при отправке.
    // Коллектор по getMessages("") всегда пуст — не запускаем его.
    if (id.isBlank()) return
    messagesJob = viewModelScope.launch {
      conversationRepository.getMessages(id).collect { messages ->
        _state.update { it.copy(messages = messages) }
      }
    }
    // Урок-диалог: включаем интерактивные карточки и запоминаем levelId (= заголовок).
    viewModelScope.launch {
      val conversation = conversationRepository.getConversation(id)
      val tutorial = conversation?.isTutorial == true
      _state.update {
        it.copy(isTutorial = tutorial, levelId = if (tutorial) conversation?.title else null)
      }
    }
  }

  /** Sends [text] into the conversation as a user message (used by interactive cards). */
  fun sendUserMessage(text: String) {
    if (text.isBlank()) return
    _state.update { it.copy(inputText = text) }
    sendMessage()
  }

  /** Marks the current lesson complete and invokes [onComplete] (e.g. navigate back). */
  fun completeLesson(onComplete: () -> Unit) {
    val levelId = _state.value.levelId ?: return
    viewModelScope.launch {
      userPreferencesRepository.completeRoadmapLevel(levelId)
      onComplete()
    }
  }

  fun handleIntent(intent: ChatIntent) {
    when (intent) {
      is ChatIntent.UpdateInput -> _state.update { it.copy(inputText = intent.text) }
      is ChatIntent.SendMessage -> sendMessage()
      is ChatIntent.CancelRequest -> cancel()
      is ChatIntent.ToggleToolTrace -> _state.update { it.copy(showToolTrace = !it.showToolTrace) }
      is ChatIntent.TogglePin -> togglePin(intent.messageId)
      is ChatIntent.ClearError -> _state.update { it.copy(error = null) }
      is ChatIntent.NavigateBack -> { /* handled by navigation */ }
    }
  }

  private fun sendMessage() {
    val text = _state.value.inputText.trim()
    if (text.isEmpty()) return

    activeJob?.cancel()

    responseStartTime = System.currentTimeMillis()
    _state.update {
      it.copy(
        inputText = "",
        isLoading = true,
        streamingText = "",
        toolTrace = emptyList(),
        error = null,
      )
    }

    activeJob = viewModelScope.launch {
      try {
        // Маршрут "chat/new" даёт пустой id — создаём диалог, иначе FK-нарушение при addMessage.
        val convId = ensureConversation(text)
        // Сначала ждём сохранения сообщения пользователя в БД
        conversationRepository.addMessage(convId, "user", text)
        // Теперь запускаем агент — история уже содержит новое сообщение
        val provider = runCatching {
          LlmProvider.valueOf(settingsRepository.getSelectedProvider())
        }.getOrDefault(LlmProvider.AUTO)
        agentLoop.execute(convId, text, provider).collect { event ->
          when (event) {
            is AgentEvent.TextDelta -> {
              _state.update { it.copy(streamingText = it.streamingText + event.content) }
            }
            is AgentEvent.ToolStarted -> {
              _state.update { it.copy(isLoading = true) }
            }
            is AgentEvent.ToolCompleted -> {
              val newTrace = com.aiopenpocket.agent.loop.ToolTrace(
                iteration = event.iteration,
                toolName = event.name,
                arguments = event.arguments,
                result = event.result,
              )
              _state.update { it.copy(toolTrace = it.toolTrace + newTrace) }
            }
            is AgentEvent.Completed -> {
              // Extract render_card specs so the Driven UI cards render inline in chat.
              val cardSpecs = event.result.toolTrace
                .filter { it.toolName == "render_card" }
                .mapNotNull { (it.result as? ToolResult.Success)?.data }

              val text = event.result.text
              if (text.isNotBlank() || cardSpecs.isNotEmpty()) {
                conversationRepository.addMessage(
                  convId,
                  "assistant",
                  text,
                  cardSpec = cardSpecs.firstOrNull(),
                )
              }
              // Any additional cards become their own card-only messages.
              cardSpecs.drop(1).forEach { spec ->
                conversationRepository.addMessage(convId, "assistant", "", cardSpec = spec)
              }
              val stats = ResponseStats(
                responseTimeMs = System.currentTimeMillis() - responseStartTime,
                completionTokens = event.result.completionTokensEstimate,
                contextTokensUsed = event.result.promptTokensEstimate,
              )
              _state.update {
                it.copy(
                  isLoading = false,
                  streamingText = "",
                  toolTrace = event.result.toolTrace,
                  lastResponseStats = stats,
                )
              }
            }
            is AgentEvent.Error -> {
              _state.update { it.copy(isLoading = false, error = event.message) }
            }
            else -> { /* no-op */ }
          }
        }
      } catch (e: Exception) {
        android.util.Log.e("ChatViewModel", "Agent loop error", e)
        _state.update { it.copy(isLoading = false, error = e.message) }
      }
    }
  }

  private suspend fun ensureConversation(firstMessage: String): String {
    _state.value.conversationId?.takeIf { it.isNotBlank() }?.let { return it }
    val title = ConversationRepository.placeholderTitle(firstMessage)
    val newId = conversationRepository.createConversation(title = title)
    _state.update { it.copy(conversationId = newId) }
    messagesJob?.cancel()
    messagesJob = viewModelScope.launch {
      conversationRepository.getMessages(newId).collect { messages ->
        _state.update { it.copy(messages = messages) }
      }
    }
    return newId
  }

  private fun togglePin(messageId: String) {
    val current = _state.value.messages.find { it.id == messageId } ?: return
    viewModelScope.launch {
      conversationRepository.setMessagePinned(messageId, !current.isPinned)
    }
  }

  private fun cancel() {
    activeJob?.cancel()
    _state.update { it.copy(isLoading = false, streamingText = "") }
  }
}
