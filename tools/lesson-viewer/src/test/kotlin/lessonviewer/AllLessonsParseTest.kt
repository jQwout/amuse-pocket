package lessonviewer

import lessonviewer.io.LessonLoader
import lessonviewer.io.LessonsDir
import lessonviewer.io.LoadedLesson
import lessonviewer.model.KNOWN_STEP_TYPES
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

/**
 * The point of the viewer as a tool: if a lesson does not parse, or it got a step type the viewer does
 * not know, that must fail here rather than look like emptiness in the window.
 */
class AllLessonsParseTest {

  private fun dir() = LessonsDir.resolve()

  @Test
  fun `каждый урок разбирается`() {
    val d = dir()
    assumeNotNull("каталог уроков не найден — тест пропущен", d)
    val failed = LessonLoader.loadAll(d!!).filterIsInstance<LoadedLesson.Failed>()
    assertTrue(
      "не разобрались: " + failed.joinToString { "${it.file.name}: ${it.message}" },
      failed.isEmpty(),
    )
  }

  @Test
  fun `все типы шагов известны вьюеру`() {
    val d = dir()
    assumeNotNull("каталог уроков не найден — тест пропущен", d)
    val unknown = LessonLoader.loadAll(d!!)
      .filterIsInstance<LoadedLesson.Ok>()
      .flatMap { ok -> ok.lesson.steps.map { ok.file.name to it.type } }
      .filter { it.second !in KNOWN_STEP_TYPES }
      .distinct()
    assertTrue(
      "неизвестные типы шагов (модель вьюера отстала от :app?): $unknown",
      unknown.isEmpty(),
    )
  }

  @Test
  fun `уроки вообще нашлись`() {
    val d = dir()
    assumeNotNull("каталог уроков не найден — тест пропущен", d)
    assertTrue("в каталоге нет ни одного урока", LessonLoader.loadAll(d!!).isNotEmpty())
  }
}
