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

/** text and the onboarding service steps (profile/persona/language), plus the not-yet-live input/challenge. */
@Composable
fun TextStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val body = listOf(step.text, step.prompt, step.task, step.hint).filter { it.isNotBlank() }
    if (body.isEmpty()) {
      Text("(пустой шаг)", style = MaterialTheme.typography.bodySmall)
    } else {
      body.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
    if (step.note.isNotBlank()) {
      Text(
        step.note,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp),
      )
    }
  }
}
