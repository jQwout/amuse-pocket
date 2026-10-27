package lessonviewer

import lessonviewer.io.LessonsDir
import lessonviewer.model.ROADMAP_ORDER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * [ROADMAP_ORDER] is a copy of the list from the app, and the copy must not drift. The test reads the
 * `:app` source as TEXT (not as a build dependency — the viewer still depends on nothing) and compares
 * the order. Reorder or add a lesson in the app — the test goes red.
 */
class RoadmapOrderTest {

  private fun roadmapSource(): File? =
    LessonsDir.resolve()
      // .../app/src/main/assets/lessons -> .../app
      ?.parentFile?.parentFile?.parentFile?.parentFile
      ?.resolve("src/main/java/com/aiopenpocket/ui/roadmap/RoadmapLevels.kt")
      ?.takeIf { it.isFile }

  @Test
  fun `порядок уроков совпадает с ROADMAP_LEVELS приложения`() {
    val src = roadmapSource()
    assumeTrue("исходник RoadmapLevels.kt не найден — тест пропущен", src != null)

    val text = src!!.readText()
    // The RoadmapLevel constructor with the id as the first argument. The data class declaration does not
    // match: right after its parenthesis comes `val`, not a quote.
    val ids = Regex("""RoadmapLevel\(\s*"([a-z0-9_]+)"""")
      .findAll(text)
      .map { it.groupValues[1] }
      .toList()

    assertTrue("в RoadmapLevels.kt не нашлось уровней — сломался разбор, а не порядок", ids.size >= 20)
    assertEquals(
      "порядок уроков разъехался с приложением — обнови ROADMAP_ORDER в LessonOrder.kt",
      ids,
      ROADMAP_ORDER,
    )
  }
}
