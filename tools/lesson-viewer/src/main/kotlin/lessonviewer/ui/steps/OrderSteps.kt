package lessonviewer.ui.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.model.sortCorrectOrder
import lessonviewer.ui.ErrorPlate

@Composable
fun SortStepView(step: LessonStep) {
  val ordered = step.sortCorrectOrder()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (step.instruction.isNotBlank()) {
      Text(step.instruction, style = MaterialTheme.typography.titleSmall)
    }
    if (ordered != null) {
      ordered.forEachIndexed { i, item ->
        Text("${i + 1}. $item", style = MaterialTheme.typography.bodyMedium)
      }
    } else {
      // The order cannot be restored — we show the author's order and say so honestly,
      // instead of making up the "correct" one.
      step.items.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
      ErrorPlate("`correct` не полная перестановка — показан авторский порядок: ${step.correct}")
    }
    if (step.explain.isNotBlank()) {
      Text(
        step.explain,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
      )
    }
  }
}

@Composable
fun MatchStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (step.instruction.isNotBlank()) {
      Text(step.instruction, style = MaterialTheme.typography.titleSmall)
    }
    step.pairs.forEach { pair ->
      Text("${pair.term} → ${pair.def}", style = MaterialTheme.typography.bodyMedium)
    }
    if (step.pairs.isEmpty()) ErrorPlate("у шага match нет пар")
  }
}
