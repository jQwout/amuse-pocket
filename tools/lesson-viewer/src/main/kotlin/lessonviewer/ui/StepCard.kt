package lessonviewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.ui.steps.ConceptStepView
import lessonviewer.ui.steps.ExampleStepView
import lessonviewer.ui.steps.FillStepView
import lessonviewer.ui.steps.MatchStepView
import lessonviewer.ui.steps.QuizStepView
import lessonviewer.ui.steps.RevealStepView
import lessonviewer.ui.steps.ScenarioStepView
import lessonviewer.ui.steps.SortStepView
import lessonviewer.ui.steps.TextStepView

/** Единственная точка входа рендера шага. Незнакомый тип — красная плашка, а не пропуск. */
@Composable
fun StepCard(step: LessonStep, index: Int) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      TypeBadge(step.type, index)
      when (step.type) {
        "text", "profile", "persona", "language", "input", "challenge" -> TextStepView(step)
        "quiz" -> QuizStepView(step)
        "scenario" -> ScenarioStepView(step)
        "reveal" -> RevealStepView(step)
        "concept" -> ConceptStepView(step)
        "example" -> ExampleStepView(step)
        "fill" -> FillStepView(step)
        "sort" -> SortStepView(step)
        "match" -> MatchStepView(step)
        "image" -> ErrorPlate("рендер картинок — Task 6")
        else -> ErrorPlate("неизвестный тип шага: «${step.type}»")
      }
    }
  }
}
