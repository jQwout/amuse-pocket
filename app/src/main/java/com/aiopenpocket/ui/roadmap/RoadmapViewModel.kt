package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.lesson.LessonRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class RoadmapViewModel(
  private val userPreferencesRepository: UserPreferencesRepository,
  private val conversationRepository: ConversationRepository,
  private val lessonRepository: LessonRepository,
) : ViewModel() {

  val roadmapProgress: StateFlow<Set<String>> =
    userPreferencesRepository.roadmapProgress
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

  /** Открытие уже идёт для этого levelId — игнорируем повторный тап, иначе двойной клик успевает
   *  создать два дубля чата до того, как первый закоммитится (см. LibraryViewModel/AgentsViewModel). */
  private val opening = mutableSetOf<String>()

  /**
   * Opens (or reuses) the lesson chat for [levelId] and returns its id via [onReady].
   * Each lesson is a tutorial conversation; PrePromptAssembler injects the lesson script.
   */
  fun openLesson(levelId: String, onReady: (String) -> Unit) {
    if (!opening.add(levelId)) return
    viewModelScope.launch {
      try {
        val level = ROADMAP_LEVELS.find { it.id == levelId } ?: return@launch
        val personaId = userPreferencesRepository.getDefaultPersonaId()
        // JSON-урок (assets/lessons/{id}.json) драйвится чат-контроллером с шага 0 — приветствие НЕ постим,
        // иначе оно собьёт счёт шагов в ChatViewModel.startOrResumeLesson. Свободные уроки (l2–l4) — постим.
        val hasJsonLesson = lessonRepository.getLessonOrNull(level.id) != null

        val existing = conversationRepository.findLatestTutorialConversation(level.id)

        val chatId = existing?.id ?: conversationRepository.createConversation(
          title = level.id,
          personaId = personaId,
          isTutorial = true,
        ).also { newId ->
          if (!hasJsonLesson) {
            conversationRepository.addMessage(
              newId,
              "assistant",
              tr(
                "Привет! Это урок «${level.title}». ${level.description} Напиши «готов», и начнём.",
                "Hi! This is the lesson “${level.title}”. ${level.description} Type “ready” and we'll begin.",
              ),
            )
          }
        }

        onReady(chatId)
      } finally {
        opening.remove(levelId)
      }
    }
  }
}
