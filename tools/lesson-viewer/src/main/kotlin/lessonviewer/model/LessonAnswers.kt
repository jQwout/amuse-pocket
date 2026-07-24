package lessonviewer.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Разбор «правильных ответов». Общее правило: битый или отсутствующий ключ НИКОГДА не превращается
 * в «правильный — первый». Молча подсвеченный не тот вариант хуже, чем честно не подсвеченный
 * ни один (в :app тот же класс бага чинил коммит f646e64).
 */

/** "scenario": 0-based индекс верного варианта, либо -1 если ключа нет или он не число. */
fun LessonStep.scenarioCorrectIndex(): Int = (correct as? JsonPrimitive)?.intOrNull ?: -1

/**
 * "sort": элементы в правильном порядке, либо null, если [LessonStep.correct] не является полной
 * перестановкой индексов [LessonStep.items].
 *
 * Семантика перестановки взята из приложения: `correct[позиция] = индекс элемента`, то есть
 * правильный порядок — `correct.map { items[it] }`. Источник — SortCardView в
 * app/src/main/java/com/aiopenpocket/ui/chat/components/DrivenUiCardViews.kt (около строки 1124),
 * там же и проверка на полную перестановку.
 */
fun LessonStep.sortCorrectOrder(): List<String>? {
  val indices = (correct as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
  val n = items.size
  val isPermutation = indices.size == n && indices.toSet() == (0 until n).toSet()
  return if (isPermutation) indices.map { items[it] } else null
}

/** У вопроса квиза есть отмеченный правильный вариант и он в диапазоне. */
fun QuizQuestion.hasCorrect(): Boolean = correct in options.indices
