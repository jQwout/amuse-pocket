package com.aiopenpocket.ui.conversations

// @trace multi-chat-conversations-b3c93b

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ConversationsListViewModel::class)
class ConversationsListViewModel(
  private val conversationRepository: ConversationRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(ConversationsListState())
  val state: StateFlow<ConversationsListState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      conversationRepository.getAllConversations().collect { conversations ->
        _state.update { it.copy(conversations = conversations, isLoading = false) }
      }
    }
  }

  /** Tap a tag to narrow the list to chats carrying it; tap the active tag again to clear. */
  fun toggleTagFilter(tag: String) {
    _state.update { it.copy(selectedTag = if (it.selectedTag == tag) null else tag) }
  }

  suspend fun createConversation(initialMessage: String? = null): String? {
    val title = initialMessage?.let { ConversationRepository.placeholderTitle(it) } ?: "Новый чат"
    val personaId = userPreferencesRepository.getDefaultPersonaId()
    val createdId = conversationRepository.createConversation(title, personaId)
    if (initialMessage != null) {
      conversationRepository.addMessage(createdId, "user", initialMessage)
    }
    return createdId
  }

  fun deleteConversation(id: String) {
    viewModelScope.launch {
      conversationRepository.deleteConversation(id)
    }
  }
}
