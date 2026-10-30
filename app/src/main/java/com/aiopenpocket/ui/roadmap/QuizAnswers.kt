package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb
// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

/**
 * The chosen options of a lesson quiz, as the string kept in `messages.cardState` ("0:2,1:0" =
 * question 0 -> option 2, question 1 -> option 0). A plain string rather than JSON so this stays a pure Kotlin
 * object that JVM tests can run without an Android JSON stub.
 *
 * Why it exists: the quiz used to keep its answers only in the composition's saved state, which is gone as
 * soon as the person leaves the lesson screen — so coming back showed an unanswered quiz.
 */
object QuizAnswers {

  /** The wire form of [answers]; null for an empty map, so a cleared quiz stores nothing. */
  fun encode(answers: Map<Int, Int>): String? =
    answers.entries
      .sortedBy { it.key }
      .joinToString(",") { "${it.key}:${it.value}" }
      .takeIf { it.isNotEmpty() }

  /**
   * Reads [state] back for [questions]. Anything that no longer fits the lesson — garbage, a question that
   * is gone, an option index past the end (the lesson JSON was edited in an update) — is dropped rather than
   * trusted: a stale index would mark a wrong option as picked.
   */
  fun decode(state: String?, questions: List<QuizQuestion>): Map<Int, Int> {
    if (state.isNullOrBlank()) return emptyMap()
    val result = linkedMapOf<Int, Int>()
    state.split(',').forEach { pair ->
      val parts = pair.split(':')
      if (parts.size != 2) return@forEach
      val q = parts[0].trim().toIntOrNull() ?: return@forEach
      val o = parts[1].trim().toIntOrNull() ?: return@forEach
      val question = questions.getOrNull(q) ?: return@forEach
      if (o in question.options.indices) result[q] = o
    }
    return result
  }
}
