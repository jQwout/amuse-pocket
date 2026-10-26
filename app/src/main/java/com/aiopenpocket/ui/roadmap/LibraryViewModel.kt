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

/** A lesson's metadata for its card in the Library. */
data class LibraryLesson(
  val id: String,
  val title: String,
  val description: String,
  val iconKey: String,
)

/**
 * The catalog of concept lessons (the order is pedagogical). `l1_welcome` is onboarding, it is not in the
 * library. Every id must match `assets/lessons/{id}.json` — LessonAssetsValidationTest checks that (hence
 * internal, not private).
 *
 * The order repeats ROADMAP_LEVELS: lessons refer to "the lesson about memory", "the lesson about tools", and in
 * the library the reader goes top to bottom just as along the path. The only difference is "RAG", which is not
 * on the path: it sits right after context engineering, because RAG is also about filling the window.
 */
internal val LIBRARY_LESSON_IDS = listOf(
  "what_is_agent",
  "how_llm_reads",
  "how_to_ask",
  "tokens_and_speed",
  "how_model_sees",
  "meta_models",
  "muse_spark_modes",
  "memory_and_context",
  "temperature_and_randomness",
  "muse_image_draws",
  "privacy_and_reliability",
  "open_weights_and_api",
  "hallucinations",
  "workflow_and_agent",
  "chaining_and_routing",
  "react_loop",
  "agent_tools",
  "agent_skills",
  "context_engineering",
  "rag",
  "tool_approval_and_limits",
  "hooks_and_lifecycle",
  "build_your_agent",
  "plan_mode",
  "goal_mode",
  "structured_plans",
  "reflection",
  "heartbeat_and_cron",
  "arena_and_swarm",
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

  /** A guard against creating a lesson chat twice on a quick double tap on a card. */
  private var opening = false

  init {
    viewModelScope.launch {
      // Independent asset reads — load them all at once rather than one by one (otherwise 16 lessons in a row delay
      // the screen's first draw by the sum of all round trips instead of the maximum of one).
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
   * Opens (or reuses) the lesson chat for [lessonId] and returns its id through [onReady]. No greeting is
   * published — the steps are driven by [com.aiopenpocket.agent.core.lesson.LessonChatController].
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
