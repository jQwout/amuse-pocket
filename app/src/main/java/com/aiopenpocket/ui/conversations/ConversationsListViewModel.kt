package com.aiopenpocket.ui.conversations

// @trace multi-chat-conversations-b3c93b

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationHeartbeatCleanup
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
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
@ViewModelKey
class ConversationsListViewModel(
  private val conversationRepository: ConversationRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val turnManager: TurnManager,
  private val conversationHeartbeatCleanup: ConversationHeartbeatCleanup,
) : ViewModel() {

  private val _state = MutableStateFlow(ConversationsListState())
  val state: StateFlow<ConversationsListState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      conversationRepository.getAllConversations().collect { conversations ->
        _state.update { it.copy(conversations = conversations, isLoading = false) }
      }
    }
    // Background turn badges: "cooking"/"done"/"error" on a conversation in the list.
    viewModelScope.launch {
      turnManager.badges.collect { statuses ->
        _state.update { it.copy(turnStatuses = statuses) }
      }
    }
  }

  /** Tap a tag to narrow the list to chats carrying it; tap the active tag again to clear. */
  fun toggleTagFilter(tag: String) {
    _state.update { it.copy(selectedTag = if (it.selectedTag == tag) null else tag) }
  }

  /** A guard against a repeated "New chat" tap: without it a quick double tap managed to go through twice
   *  before the first call created the conversation, spawning two empty "New chat" entries in a row. Accessed
   *  only from the main thread (viewModelScope), so a plain flag is enough. */
  private var creatingConversation = false

  suspend fun createConversation(): String? {
    if (creatingConversation) return null
    creatingConversation = true
    try {
      // A "New chat" tap: reuse an already created empty chat if the user just opened it and wrote nothing,
      // otherwise every repeated tap would spawn a new empty "New chat".
      conversationRepository.findReusableNewChat()?.let { return it.id }
      val personaId = userPreferencesRepository.getDefaultPersonaId()
      return conversationRepository.createConversation(tr("Новый чат", "New chat"), personaId)
    } finally {
      creatingConversation = false
    }
  }

  fun deleteConversation(id: String) {
    // Deleting a conversation with an active turn: first the turn is cancelled, then this conversation's
    // background heartbeats (they have no FK to conversations, the cascade will not reach them — otherwise a
    // periodic WorkManager job would live forever), then the DB rows are deleted.
    turnManager.cancel(id)
    viewModelScope.launch {
      conversationHeartbeatCleanup.cancelForConversation(id)
      conversationRepository.deleteConversation(id)
    }
  }

  /** Toggles "favorite" for a conversation — favorites rise to the top of the list. */
  fun toggleFavorite(id: String, isFavorite: Boolean) {
    viewModelScope.launch {
      conversationRepository.setFavorite(id, !isFavorite)
    }
  }
}
