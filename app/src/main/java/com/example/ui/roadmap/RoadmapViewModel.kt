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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(RoadmapViewModel::class)
class RoadmapViewModel(
  private val userPreferencesRepository: UserPreferencesRepository,
  private val conversationRepository: ConversationRepository,
) : ViewModel() {

  val roadmapProgress: StateFlow<Set<String>> =
    userPreferencesRepository.roadmapProgress
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

  /**
   * Opens (or reuses) the lesson chat for [levelId] and returns its id via [onReady].
   * Each lesson is a tutorial conversation; PrePromptAssembler injects the lesson script.
   */
  fun openLesson(levelId: String, onReady: (String) -> Unit) {
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
        // План-мод запускается сразу в деле: просим составную задачу, а не «готов».
        val greeting = if (level.id == "plan_mode") {
          "Это урок «${level.title}». План-режим уже включён (иконка дерева вверху). " +
            "Дай мне любую составную задачу — например «спланируй продуктивное утро» " +
            "или «составь план изучения Kotlin на неделю» — и увидишь, как я сначала " +
            "строю план, потом выполняю по шагам, потом собираю итог."
        } else {
          "Привет! Это урок «${level.title}». ${level.description} Напиши «готов», и начнём."
        }
        conversationRepository.addMessage(newId, "assistant", greeting)
      }

      onReady(chatId)
    }
  }
}
