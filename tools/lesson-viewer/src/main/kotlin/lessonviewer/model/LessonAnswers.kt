package lessonviewer.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Parsing the "correct answers". The general rule: a broken or missing key NEVER turns into "the first
 * one is correct". A wrong option silently highlighted is worse than honestly highlighting none (in :app
 * commit f646e64 fixed the same class of bug).
 */

/** "scenario": the 0-based index of the right option, or -1 if the key is missing or not a number. */
fun LessonStep.scenarioCorrectIndex(): Int = (correct as? JsonPrimitive)?.intOrNull ?: -1

/**
 * "sort": the items in the correct order, or null if [LessonStep.correct] is not a full permutation of
 * the indices of [LessonStep.items].
 *
 * The permutation semantics are taken from the app: `correct[position] = item index`, so the correct
 * order is `correct.map { items[it] }`. The source is SortCardView in
 * app/src/main/java/com/aiopenpocket/ui/chat/components/LessonCardViews.kt, which also has the
 * full-permutation check.
 */
fun LessonStep.sortCorrectOrder(): List<String>? {
  val indices = (correct as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
  val n = items.size
  val isPermutation = indices.size == n && indices.toSet() == (0 until n).toSet()
  return if (isPermutation) indices.map { items[it] } else null
}

/** A quiz question has a marked correct option, and it is in range. */
fun QuizQuestion.hasCorrect(): Boolean = correct in options.indices
