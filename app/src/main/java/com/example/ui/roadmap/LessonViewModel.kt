package com.aiopenpocket.ui.roadmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(LessonViewModel::class)
class LessonViewModel(
  private val conversationRepository: ConversationRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  fun isCompleted(levelId: String): Flow<Boolean> =
    userPreferencesRepository.roadmapProgress.map { levelId in it }

  fun startLesson(levelId: String, onStart: (String) -> Unit) {
    viewModelScope.launch {
      val level = ROADMAP_LEVELS.find { it.id == levelId } ?: return@launch
      val personaId = userPreferencesRepository.getDefaultPersonaId()

      val existing = conversationRepository.getAllConversations()
        .first()
        .find { it.title == level.id && it.isTutorial }

      val chatId = existing?.id ?: conversationRepository.createConversation(
        title = level.id,
        personaId = personaId,
        isTutorial = true,
      ).also { newId ->
        conversationRepository.addMessage(
          newId,
          "assistant",
          "Привет! Начинаем урок: ${level.title}. ${level.description}. Напиши \"готов\", когда будешь готов начать.",
        )
      }

      onStart(chatId)
    }
  }

  fun completeLevel(levelId: String) {
    viewModelScope.launch {
      userPreferencesRepository.completeRoadmapLevel(levelId)
    }
  }
}
