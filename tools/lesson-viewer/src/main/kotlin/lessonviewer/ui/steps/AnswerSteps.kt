package lessonviewer.ui.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.model.hasCorrect
import lessonviewer.model.scenarioCorrectIndex
import lessonviewer.ui.ErrorPlate

private val CorrectBg = Color(0x2200AA00)

@Composable
private fun Option(text: String, correct: Boolean) {
  Box(
    Modifier
      .fillMaxWidth()
      .background(if (correct) CorrectBg else Color.Transparent, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    Text(
      (if (correct) "✓ " else "· ") + text,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (correct) FontWeight.SemiBold else FontWeight.Normal,
    )
  }
}

@Composable
private fun Explain(text: String) {
  if (text.isNotBlank()) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
  }
}

@Composable
fun QuizStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    step.questions.forEach { q ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(q.q, style = MaterialTheme.typography.titleSmall)
        q.options.forEachIndexed { i, opt -> Option(opt, correct = q.hasCorrect() && i == q.correct) }
        if (!q.hasCorrect()) ErrorPlate("у вопроса нет корректного `correct` (${q.correct})")
        Explain(q.explain)
      }
    }
    if (step.questions.isEmpty()) ErrorPlate("у шага quiz нет вопросов")
  }
}

@Composable
fun ScenarioStepView(step: LessonStep) {
  val correctIndex = step.scenarioCorrectIndex()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (step.situation.isNotBlank()) Text(step.situation, style = MaterialTheme.typography.bodyLarge)
    if (step.q.isNotBlank()) Text(step.q, style = MaterialTheme.typography.titleSmall)
    step.options.forEachIndexed { i, opt -> Option(opt, correct = i == correctIndex) }
    if (correctIndex !in step.options.indices) {
      ErrorPlate("`correct` отсутствует или битый ($correctIndex)")
    }
    Explain(step.explain)
  }
}

@Composable
fun RevealStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(step.question, style = MaterialTheme.typography.titleSmall)
    Text(step.answer, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
fun ConceptStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(step.term, style = MaterialTheme.typography.titleSmall)
    Text(step.definition, style = MaterialTheme.typography.bodyLarge)
    if (step.example.isNotBlank()) Text(step.example, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
fun ExampleStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (step.label.isNotBlank()) Text(step.label, style = MaterialTheme.typography.titleSmall)
    step.parts.forEach { part ->
      Column {
        Text(part.text, style = MaterialTheme.typography.bodyMedium)
        val meta = listOf(part.role, part.note).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
fun FillStepView(step: LessonStep) {
  // The answer is put right into the blank: the viewer is for proofreading, not for taking the lesson.
  val hasBlank = step.template.contains("___")
  val filled = if (hasBlank) step.template.replace("___", "【${step.answer}】") else step.template
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(filled, style = MaterialTheme.typography.bodyLarge)
    if (!hasBlank) ErrorPlate("в `template` нет пропуска `___`, ответ: «${step.answer}»")
  }
}
