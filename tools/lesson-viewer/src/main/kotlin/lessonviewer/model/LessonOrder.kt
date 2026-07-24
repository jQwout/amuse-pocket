package lessonviewer.model

import lessonviewer.io.LoadedLesson

/**
 * Педагогический порядок уроков — копия `ROADMAP_LEVELS` из
 * `app/src/main/java/com/aiopenpocket/ui/roadmap/RoadmapScreen.kt`.
 *
 * Копия, а не зависимость: вьюер не связан с `:app` кодом (решение 2.1 спеки). Расхождение
 * ловится `lessonviewer.RoadmapOrderTest` — он читает исходник приложения как ТЕКСТ и сверяет
 * список, поэтому переставленный или добавленный урок роняет тест, а не тихо ломает порядок.
 */
val ROADMAP_ORDER: List<String> = listOf(
  "l1_welcome",
  "how_llm_reads",
  "how_to_ask",
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
  "heartbeat_and_cron",
  "chaining_and_routing",
  "arena_and_swarm",
  "hooks_and_lifecycle",
  "build_your_agent",
  "agent_blueprint",
)

/** Урок в списке: его место в пути (или его отсутствие). */
data class OrderedLesson(
  val loaded: LoadedLesson,
  /** Номер в пути, начиная с 1; null — урока нет в роадмапе. */
  val roadmapNumber: Int?,
)

/** id урока = имя файла без расширения; именно по нему роадмап и адресует уроки. */
private fun LoadedLesson.lessonId(): String = file.name.removeSuffix(".json")

/**
 * Уроки в порядке пути, а следом — те, которых в пути нет (по алфавиту). Вторые не выбрасываем:
 * они лежат в ассетах, и то, что из приложения до них не дойти, — это как раз то, что полезно
 * видеть.
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
