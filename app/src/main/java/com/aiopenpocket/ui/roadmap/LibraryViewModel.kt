package com.aiopenpocket.ui.roadmap

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.lesson.LessonRepository
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Метаданные урока для карточки в Библиотеке. */
data class LibraryLesson(
  val id: String,
  val title: String,
  val description: String,
  val iconKey: String,
)

/**
 * Каталог уроков-концепций (порядок — педагогический). `l1_welcome` — это онбординг, в библиотеке его нет.
 * Каждый id должен соответствовать `assets/lessons/{id}.json` — это сверяет LessonAssetsValidationTest
 * (потому и internal, а не private).
 */
internal val LIBRARY_LESSON_IDS = listOf(
  "how_llm_reads",
  "how_to_ask",
  "context_engineering",
  "tokens_and_speed",
  "temperature_and_randomness",
  "privacy_and_reliability",
  "hallucinations",
  "workflow_and_agent",
  "react_loop",
  "agent_tools",
  "tool_approval_and_limits",
  "memory_and_context",
  "agent_skills",
  "rag",
  "plan_mode",
  "goal_mode",
  "structured_plans",
  "reflection",
  "heartbeat_and_cron",
  "chaining_and_routing",
  "arena_and_swarm",
  "hooks_and_lifecycle",
  "build_your_agent",
  "agent_blueprint",
)

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class LibraryViewModel(
  private val lessonRepository: LessonRepository,
  private val conversationRepository: ConversationRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val _lessons = MutableStateFlow<List<LibraryLesson>>(emptyList())
  val lessons: StateFlow<List<LibraryLesson>> = _lessons.asStateFlow()

  val completed: StateFlow<Set<String>> =
    userPreferencesRepository.roadmapProgress
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

  /** Защита от двойного создания lesson-чата при быстром двойном тапе по карточке. */
  private var opening = false

  init {
    viewModelScope.launch {
      // Независимые чтения ассетов — грузим все разом, а не по одному (16 уроков подряд иначе
      // задерживают первую отрисовку экрана суммой всех round-trip'ов вместо максимума одного).
      val loaded = LIBRARY_LESSON_IDS.map { id ->
        async { id to lessonRepository.getLessonOrNull(id) }
      }.awaitAll().mapNotNull { (id, lesson) ->
        lesson?.let {
          LibraryLesson(
            id = it.id.ifBlank { id },
            title = it.title.ifBlank { id },
            description = it.description,
            iconKey = it.iconKey,
          )
        }
      }
      _lessons.update { loaded }
    }
  }

  /**
   * Открывает (или переиспользует) lesson-чат для [lessonId] и возвращает его id через [onReady].
   * Приветствие НЕ публикуем — шаги драйвит [com.aiopenpocket.agent.core.lesson.LessonChatController].
   */
  fun openLesson(lessonId: String, onReady: (String) -> Unit) {
    if (opening) return
    opening = true
    viewModelScope.launch {
      try {
        val personaId = userPreferencesRepository.getDefaultPersonaId()
        val existing = conversationRepository.findLatestTutorialConversation(lessonId)
        val chatId = existing?.id ?: conversationRepository.createConversation(
          title = lessonId,
          personaId = personaId,
          isTutorial = true,
        )
        onReady(chatId)
      } finally {
        opening = false
      }
    }
  }
}
