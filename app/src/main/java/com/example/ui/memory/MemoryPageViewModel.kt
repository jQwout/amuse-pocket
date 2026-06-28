package com.aiopenpocket.ui.memory

// @trace agent-memory-05e545

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MemoryPageState(
  val title: String = "",
  val summary: String? = null,
  val tags: List<String> = emptyList(),
  val messages: List<Message> = emptyList(),
)

/** Read-only view of a single conversation's memory: its annotation (title/summary/tags) + log. */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(MemoryPageViewModel::class)
class MemoryPageViewModel(
  private val conversationRepository: ConversationRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(MemoryPageState())
  val state: StateFlow<MemoryPageState> = _state.asStateFlow()

  private var messagesJob: Job? = null
  private var loadedId: String? = null

  fun load(conversationId: String) {
    if (conversationId.isBlank() || conversationId == loadedId) return
    loadedId = conversationId

    viewModelScope.launch {
      val conversation = conversationRepository.getConversation(conversationId)
      _state.update {
        it.copy(
          title = conversation?.title.orEmpty(),
          summary = conversation?.summary,
          tags = conversation?.tags.orEmpty(),
        )
      }
    }

    messagesJob?.cancel()
    messagesJob = viewModelScope.launch {
      conversationRepository.getMessages(conversationId).collect { messages ->
        _state.update { it.copy(messages = messages.filter { m -> m.content.isNotBlank() }) }
      }
    }
  }
}
