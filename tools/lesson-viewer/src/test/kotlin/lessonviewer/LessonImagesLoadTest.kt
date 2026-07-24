package lessonviewer

import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.Density
import lessonviewer.io.LessonLoader
import lessonviewer.io.LessonsDir
import lessonviewer.io.LoadedLesson
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import java.io.File

/**
 * Риск 10.2 спеки: картинки уроков рисовались под Coil в приложении, а вьюер грузит их
 * штатным loadSvgPainter. Проверяем машиной, а не глазами: каждая картинка, на которую
 * ссылается шаг "image", должна существовать и разбираться.
 */
class LessonImagesLoadTest {

  private fun imageRefs(dir: File): List<Pair<String, String>> =
    LessonLoader.loadAll(dir)
      .filterIsInstance<LoadedLesson.Ok>()
      .flatMap { ok ->
        ok.lesson.steps
          .filter { it.type == "image" }
          .map { step ->
            val relative = when {
              step.imageKey.isNotBlank() -> "lessons/images/${step.imageKey}.svg"
              else -> step.asset
            }
            ok.file.name to relative
          }
      }

  @Test
  fun `все картинки уроков существуют и разбираются`() {
    val dir = LessonsDir.resolve()
    assumeNotNull("каталог уроков не найден — тест пропущен", dir)
    val assetsRoot = dir!!.parentFile
    val density = Density(1f)

    val refs = imageRefs(dir)
    assertTrue("в уроках не нашлось ни одного шага image — проверка бессмысленна", refs.isNotEmpty())

    val broken = refs.mapNotNull { (lesson, relative) ->
      when {
        relative.isBlank() -> "$lesson: у шага image нет ни asset, ни imageKey"
        else -> {
          val f = File(assetsRoot, relative)
          when {
            !f.isFile -> "$lesson: нет файла $relative"
            !f.name.endsWith(".svg", ignoreCase = true) -> "$lesson: не SVG — $relative"
            runCatching { f.inputStream().use { loadSvgPainter(it, density) } }.isFailure ->
              "$lesson: SVG не разбирается — $relative"

            else -> null
          }
        }
      }
    }

    assertTrue("битые картинки: $broken", broken.isEmpty())
  }
}
