package com.aiopenpocket.ui.conversations

// @trace multi-chat-conversations-b3c93b

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.TurnManager
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
  private val turnManager: TurnManager,
) : ViewModel() {

  private val _state = MutableStateFlow(ConversationsListState())
  val state: StateFlow<ConversationsListState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      conversationRepository.getAllConversations().collect { conversations ->
        _state.update { it.copy(conversations = conversations, isLoading = false) }
      }
    }
    // Бейджи фоновых ходов: «варится»/«готово»/«ошибка» у диалога в списке.
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

  /** Защита от повторного тапа «Новый чат»: без неё быстрый двойной тап успевал пройти оба раза
   *  до того, как первый вызов создаст диалог, и плодил два пустых «Новый чат» подряд. Доступ
   *  только с главного потока (viewModelScope), поэтому обычного флага достаточно. */
  private var creatingConversation = false

  suspend fun createConversation(initialMessage: String? = null): String? {
    if (creatingConversation) return null
    creatingConversation = true
    try {
      // Без начального сообщения — это тап «Новый чат»: переиспользуем уже созданный пустой чат,
      // если пользователь просто открывал его и не написал ничего, иначе каждый повторный тап
      // плодил бы новый пустой «Новый чат» в списке диалогов.
      if (initialMessage == null) {
        conversationRepository.findReusableNewChat()?.let { return it.id }
      }
      val title = initialMessage?.let { ConversationRepository.placeholderTitle(it) } ?: "Новый чат"
      val personaId = userPreferencesRepository.getDefaultPersonaId()
      val createdId = conversationRepository.createConversation(title, personaId)
      if (initialMessage != null) {
        conversationRepository.addMessage(createdId, "user", initialMessage)
      }
      return createdId
    } finally {
      creatingConversation = false
    }
  }

  fun deleteConversation(id: String) {
    // Удаление диалога с активным ходом — сперва гасим ход, потом удаляем строки БД.
    turnManager.cancel(id)
    viewModelScope.launch {
      conversationRepository.deleteConversation(id)
    }
  }

  /** Переключает «избранное» для диалога — избранные всплывают в начало списка. */
  fun toggleFavorite(id: String, isFavorite: Boolean) {
    viewModelScope.launch {
      conversationRepository.setFavorite(id, !isFavorite)
    }
  }
}
