package com.aiopenpocket.ui.roadmap.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.roadmap.LESSON_QUIZZES
import com.aiopenpocket.ui.roadmap.QuizQuestion
import com.aiopenpocket.ui.theme.MyApplicationTheme

private val CorrectGreen = Color(0xFF4CAF50)

/**
 * Renders the lesson quiz: a list of multiple-choice questions, each revealing
 * correct/incorrect feedback once answered. Non-blocking — purely a self-check.
 * Hosted by both the standalone QuizScreen and the onboarding quiz step.
 */
@Composable
fun QuizContent(
  questions: List<QuizQuestion>,
  modifier: Modifier = Modifier,
) {
  // questionIndex -> selected option index
  val answers = remember { mutableStateMapOf<Int, Int>() }

  Column(modifier = modifier) {
    questions.forEachIndexed { qIndex, question ->
      val selected = answers[qIndex]
      Text(
        text = "Вопрос ${qIndex + 1} из ${questions.size}",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = question.question,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
      )
      Spacer(Modifier.height(8.dp))

      question.options.forEachIndexed { i, option ->
        val answered = selected != null
        val isCorrect = i == question.correctIndex
        val isPicked = selected == i
        val borderColor = when {
          !answered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
          isCorrect -> CorrectGreen
          isPicked -> MaterialTheme.colorScheme.error
          else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        }
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = selected == null) { answers[qIndex] = i }
            .padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(text = option, style = MaterialTheme.typography.bodyLarge)
          when {
            answered && isCorrect -> Icon(Icons.Default.Check, null, tint = CorrectGreen)
            answered && isPicked -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
          }
        }
      }

      if (selected != null) {
        Spacer(Modifier.height(6.dp))
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        ) {
          Text(
            text = question.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (qIndex != questions.lastIndex) {
        Spacer(Modifier.height(20.dp))
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 700)
@Composable
private fun QuizContentPreview() {
  MyApplicationTheme {
    QuizContent(questions = LESSON_QUIZZES["l1_welcome"].orEmpty())
  }
}
