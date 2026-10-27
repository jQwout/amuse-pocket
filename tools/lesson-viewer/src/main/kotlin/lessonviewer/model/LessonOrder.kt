package lessonviewer.model

import lessonviewer.io.LoadedLesson

/**
 * The pedagogical order of the lessons — a copy of `ROADMAP_LEVELS` from
 * `app/src/main/java/com/aiopenpocket/ui/roadmap/RoadmapLevels.kt`.
 *
 * A copy, not a dependency: the viewer is not tied to `:app` code (decision 2.1 of the spec). A mismatch
 * is caught by `lessonviewer.RoadmapOrderTest` — it reads the app's source as TEXT and compares the list,
 * so a reordered or added lesson fails the test instead of quietly breaking the order.
 */
val ROADMAP_ORDER: List<String> = listOf(
  "l1_welcome",
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

/** A lesson in the list: its place in the path (or its absence). */
data class OrderedLesson(
  val loaded: LoadedLesson,
  /** The number in the path, starting from 1; null — the lesson is not in the roadmap. */
  val roadmapNumber: Int?,
)

/** The lesson id = the file name without the extension; this is exactly how the roadmap addresses lessons. */
private fun LoadedLesson.lessonId(): String = file.name.removeSuffix(".json")

/**
 * The lessons in path order, followed by those not in the path (alphabetically). The latter are not
 * thrown away: they are not lost — the app opens them through the "Library" (`LIBRARY_LESSON_IDS`) — but
 * they are not part of the ordered path, and that is useful to see when proofreading.
 */
fun orderLessons(lessons: List<LoadedLesson>): List<OrderedLesson> {
  val positions = ROADMAP_ORDER.withIndex().associate { (i, id) -> id to i }
  val (inPath, offPath) = lessons.partition { it.lessonId() in positions }
  return inPath
    .sortedBy { positions.getValue(it.lessonId()) }
    .map { OrderedLesson(it, positions.getValue(it.lessonId()) + 1) } +
    offPath
      .sortedBy { it.file.name }
      .map { OrderedLesson(it, null) }
}
