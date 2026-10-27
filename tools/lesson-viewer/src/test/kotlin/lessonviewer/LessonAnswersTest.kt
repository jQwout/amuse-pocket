package lessonviewer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import lessonviewer.model.LessonStep
import lessonviewer.model.QuizQuestion
import lessonviewer.model.hasCorrect
import lessonviewer.model.scenarioCorrectIndex
import lessonviewer.model.sortCorrectOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only non-trivial logic of the viewer — and the only one capable of lying: showing the wrong option
 * as "correct". The general rule of all the tests below: a broken key never turns into "the first one is
 * correct".
 */
class LessonAnswersTest {

  private fun arr(vararg v: Int) = Json.parseToJsonElement(v.joinToString(",", "[", "]"))

  // --- scenario ---

  @Test
  fun `scenario берёт индекс из числа`() {
    val step = LessonStep(type = "scenario", correct = JsonPrimitive(2))
    assertEquals(2, step.scenarioCorrectIndex())
  }

  @Test
  fun `scenario без ключа даёт минус один, а не ноль`() {
    val step = LessonStep(type = "scenario", correct = null)
    assertEquals(-1, step.scenarioCorrectIndex())
  }

  @Test
  fun `scenario с массивом вместо числа даёт минус один`() {
    val step = LessonStep(type = "scenario", correct = arr(0, 1))
    assertEquals(-1, step.scenarioCorrectIndex())
  }

  // --- sort ---

  @Test
  fun `sort раскладывает элементы по перестановке`() {
    // A real step from agent_skills.json: correct = [1,2,0]
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(1, 2, 0))
    assertEquals(listOf("б", "в", "а"), step.sortCorrectOrder())
  }

  @Test
  fun `sort с неполной перестановкой не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(0, 1))
    assertNull(step.sortCorrectOrder())
  }

  @Test
  fun `sort с дублями не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(0, 0, 1))
    assertNull(step.sortCorrectOrder())
  }

  @Test
  fun `sort с индексом вне диапазона не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(0, 1, 7))
    assertNull(step.sortCorrectOrder())
  }

  @Test
  fun `sort без ключа не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б"), correct = null)
    assertNull(step.sortCorrectOrder())
  }

  // --- quiz ---

  @Test
  fun `quiz с корректным индексом имеет ответ`() {
    assertTrue(QuizQuestion(q = "?", options = listOf("а", "б"), correct = 1).hasCorrect())
  }

  @Test
  fun `quiz с минус единицей ответа не имеет`() {
    assertFalse(QuizQuestion(q = "?", options = listOf("а", "б"), correct = -1).hasCorrect())
  }

  @Test
  fun `quiz с индексом вне диапазона ответа не имеет`() {
    assertFalse(QuizQuestion(q = "?", options = listOf("а", "б"), correct = 5).hasCorrect())
  }
}
