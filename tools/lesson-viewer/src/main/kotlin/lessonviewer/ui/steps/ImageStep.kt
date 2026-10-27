package lessonviewer.ui.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.ui.ErrorPlate
import java.io.File

/**
 * The picture of an "image" step. Paths in lessons are counted from `app/src/main/assets`, so [assetsRoot]
 * is the parent of the lessons directory. The `url` field (a network fallback) is deliberately not
 * supported: the viewer works with the disk, not the network.
 */
@Composable
fun ImageStepView(step: LessonStep, assetsRoot: File?) {
  // imageKey is a shorthand for a picture under lessons/images with the .svg extension.
  val relative = when {
    step.imageKey.isNotBlank() -> "lessons/images/${step.imageKey}.svg"
    step.asset.isNotBlank() -> step.asset
    else -> ""
  }
  val file = if (relative.isBlank() || assetsRoot == null) null else File(assetsRoot, relative)
  val density = LocalDensity.current

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    when {
      relative.isBlank() && step.url.isNotBlank() ->
        ErrorPlate("картинка только по сети, вьюер её не грузит: ${step.url}")

      file == null ->
        ErrorPlate("у шага image нет ни asset, ни imageKey")

      !file.isFile ->
        ErrorPlate("картинка не найдена: ${file.path}")

      !file.name.endsWith(".svg", ignoreCase = true) ->
        ErrorPlate("поддерживается только SVG, а тут: ${file.name}")

      else -> {
        val painter = remember(file.path) {
          runCatching { file.inputStream().use { loadSvgPainter(it, density) } }.getOrNull()
        }
        if (painter == null) {
          ErrorPlate("SVG не рисуется: ${file.path}")
        } else {
          Image(
            painter,
            contentDescription = step.alt,
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
          )
        }
      }
    }
    val caption = step.caption.ifBlank { step.alt }
    if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall)
  }
}
