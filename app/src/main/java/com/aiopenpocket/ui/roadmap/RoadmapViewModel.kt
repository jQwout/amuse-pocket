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

  /** Opening is already in progress for this levelId — ignore the repeated tap, otherwise a double click manages to
   *  create two duplicate chats before the first one commits (see LibraryViewModel/AgentsViewModel). */
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
        // A JSON lesson (assets/lessons/{id}.json) is driven by the chat controller from step 0 — no greeting is posted:
        // the controller publishes the lesson's first step itself (and back when the lesson cursor was derived from
        // the message count in ChatViewModel.startOrResumeLesson, a greeting also threw the count off). Free
        // lessons (l2–l4) do get one.
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
