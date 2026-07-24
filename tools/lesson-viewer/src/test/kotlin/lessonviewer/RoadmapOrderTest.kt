package lessonviewer

import lessonviewer.io.LessonsDir
import lessonviewer.model.ROADMAP_ORDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [ROADMAP_ORDER] — копия списка из приложения, и копия обязана не разъезжаться. Тест читает
 * исходник `:app` как ТЕКСТ (не как зависимость сборки — вьюер по-прежнему ни от чего не зависит)
 * и сверяет порядок. Переставили или добавили урок в приложении — тест краснеет.
 */
class RoadmapOrderTest {

  private fun roadmapSource(): File? =
    LessonsDir.resolve()
      // .../app/src/main/assets/lessons -> .../app
      ?.parentFile?.parentFile?.parentFile?.parentFile
      ?.resolve("src/main/java/com/aiopenpocket/ui/roadmap/RoadmapScreen.kt")
      ?.takeIf { it.isFile }

  @Test
  fun `порядок уроков совпадает с ROADMAP_LEVELS приложения`() {
    val src = roadmapSource()
    assumeTrue("исходник RoadmapScreen.kt не найден — тест пропущен", src != null)

    val text = src!!.readText()
    // Конструктор RoadmapLevel с id первым аргументом. Объявление data class сюда не попадает:
    // там сразу после скобки идёт `val`, а не кавычка.
    val ids = Regex("""RoadmapLevel\(\s*"([a-z0-9_]+)"""")
      .findAll(text)
      .map { it.groupValues[1] }
      .toList()

    assertTrue("в RoadmapScreen.kt не нашлось уровней — сломался разбор, а не порядок", ids.size >= 20)
    assertEquals(
      "порядок уроков разъехался с приложением — обнови ROADMAP_ORDER в LessonOrder.kt",
      ids,
      ROADMAP_ORDER,
    )
  }
}
