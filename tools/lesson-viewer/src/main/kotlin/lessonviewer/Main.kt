package lessonviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Уроки",
    state = rememberWindowState(size = DpSize(1280.dp, 900.dp)),
  ) {
    MaterialTheme {
      Surface { Text("окно живо") }
    }
  }
}
