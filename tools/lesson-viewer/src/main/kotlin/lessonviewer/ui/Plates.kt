package lessonviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Красная плашка: то, что в приложении молча превращается в пустоту, тут должно быть видно. */
@Composable
fun ErrorPlate(text: String) {
  Box(
    Modifier
      .fillMaxWidth()
      .background(Color(0x22FF0000), RoundedCornerShape(6.dp))
      .padding(10.dp),
  ) {
    Text(text, color = Color(0xFFB00020), style = MaterialTheme.typography.bodySmall)
  }
}

/** Номер шага и его тип — чтобы можно было назвать шаг вслух, сверяясь с приложением. */
@Composable
fun TypeBadge(type: String, index: Int) {
  Box(
    Modifier
      .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text("#$index · $type", style = MaterialTheme.typography.labelSmall)
  }
}
