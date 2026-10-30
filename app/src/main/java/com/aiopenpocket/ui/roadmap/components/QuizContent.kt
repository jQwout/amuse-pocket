package com.aiopenpocket.ui.roadmap.components

// @trace ai-academy-onboarding-5d51bb

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.roadmap.QuizAnswers
import com.aiopenpocket.ui.roadmap.QuizQuestion
import com.aiopenpocket.ui.theme.MyApplicationTheme
import kotlin.random.Random

private val CorrectGreen = Color(0xFF4CAF50)

/**
 * Deterministically shuffled indexes [0, size): the seed is computed from the content, so the order is the same
 * on every display — saved answers and highlighting do not slide after recycling, a rotation or reopening. It
 * cures "the right answer is always first" in the authored JSON without touching the content. [avoidIdentity]
 * is for the match card: the original order would give away all the pairs at once.
 */
internal fun stableShuffledIndices(size: Int, seed: Int, avoidIdentity: Boolean = false): List<Int> {
  val order = (0 until size).shuffled(Random(seed))
  val isIdentity = order.withIndex().all { (i, v) -> i == v }
  return if (avoidIdentity && size > 1 && isIdentity) order.drop(1) + order.first() else order
}

/** The seed for shuffling a question's options — stable as long as the question itself does not change. */
private fun QuizQuestion.shuffleSeed(): Int =
  question.hashCode() * 31 + options.fold(0) { acc, o -> acc * 31 + o.hashCode() }

/**
 * Renders the lesson quiz: a list of multiple-choice questions, each revealing
 * correct/incorrect feedback once answered. Non-blocking — purely a self-check.
 * Hosted by the lesson quiz card in chat (DrivenUiCardViews).
 */
@Composable
fun QuizContent(
  questions: List<QuizQuestion>,
  modifier: Modifier = Modifier,
  /** Fires once every question has an answer — used by the in-chat lesson quiz to advance. */
  onAllAnswered: (() -> Unit)? = null,
  /**
   * Answers saved earlier (see QuizAnswers): they pre-fill the quiz when the lesson is reopened. Read once —
   * after that the live state is the source of truth.
   */
  savedState: String? = null,
  /** Fires with the new encoded answers on every change (null = cleared), for the caller to persist. */
  onStateChanged: ((String?) -> Unit)? = null,
) {
  val restored = remember(questions) { QuizAnswers.decode(savedState, questions) }
  // questionIndex -> selected option index. rememberSaveable so the answers survive the item being recycled in
  // LazyColumn, a rotation and process death (otherwise a passed quiz is redrawn empty).
  val answers = rememberSaveable(
    saver = listSaver<SnapshotStateMap<Int, Int>, Int>(
      save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
      restore = { flat ->
        mutableStateMapOf<Int, Int>().apply {
          var i = 0
          while (i + 1 < flat.size) {
            put(flat[i], flat[i + 1])
            i += 2
          }
        }
      },
    ),
  ) { mutableStateMapOf<Int, Int>().apply { putAll(restored) } }

  // onAllAnswered must fire EXACTLY once per life of the saved state. rememberSaveable restores an already full map
  // after a rotation/process death/the item being recycled in LazyColumn — without this flag LaunchedEffect would
  // call the callback again and skip the lesson an extra step.
  // A quiz that comes back already complete from storage has, by definition, already moved the lesson on.
  val fired = rememberSaveable { mutableStateOf(questions.isNotEmpty() && restored.size == questions.size) }
  LaunchedEffect(answers.size) {
    if (!fired.value && questions.isNotEmpty() && answers.size == questions.size) {
      fired.value = true
      onAllAnswered?.invoke()
    }
  }

  Column(modifier = modifier) {
    questions.forEachIndexed { qIndex, question ->
      val selected = answers[qIndex]
      Text(
        text = tr("Вопрос ${qIndex + 1} из ${questions.size}", "Question ${qIndex + 1} of ${questions.size}"),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(4.dp))
      Text(
        text = question.question,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(8.dp))

      // The options are shown in a shuffled (but stable) order; answers stores the ORIGINAL option index — checking
      // against correctIndex and restoring saved answers do not depend on the display order.
      val displayOrder = remember(question) {
        stableShuffledIndices(question.options.size, question.shuffleSeed())
      }
      displayOrder.forEach { i ->
        val option = question.options[i]
        val answered = selected != null
        // correctIndex < 0 — the right option is not set/broken (see f646e64): nothing is highlighted as "right/wrong",
        // so as not to mislead with a false key.
        val hasKey = question.correctIndex >= 0
        val isCorrect = hasKey && i == question.correctIndex
        val isPicked = selected == i
        val borderColor = when {
          !answered -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
          isCorrect -> CorrectGreen
          hasKey && isPicked -> MaterialTheme.colorScheme.error
          isPicked -> MaterialTheme.colorScheme.outline
          else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        }
        // A translucent backing separates an option from the aurora (otherwise dark on dark merges); for answered ones
        // right/wrong is additionally highlighted with a colored wash — it strengthens the feedback.
        val optionBg = when {
          answered && isCorrect -> CorrectGreen.copy(alpha = 0.12f)
          answered && hasKey && isPicked -> MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
          else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        }
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(optionBg)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(enabled = selected == null) {
              answers[qIndex] = i
              onStateChanged?.invoke(QuizAnswers.encode(answers))
            }
            .padding(14.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = option,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
          )
          when {
            answered && isCorrect -> Icon(Icons.Default.Check, null, tint = CorrectGreen)
            answered && hasKey && isPicked -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
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

    // The result: the score over the questions with a key + a retake. "Take it again" clears only the answers: fired
    // is not touched, so the lesson does not advance a second time — the retake is purely for self-checking.
    if (questions.isNotEmpty() && answers.size == questions.size) {
      val keyed = questions.count { it.correctIndex >= 0 }
      val correctCount = questions.withIndex()
        .count { (i, q) -> q.correctIndex >= 0 && answers[i] == q.correctIndex }
      Spacer(Modifier.height(16.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
          .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (keyed > 0) {
            tr("Верно: $correctCount из $keyed", "Correct: $correctCount of $keyed")
          } else {
            tr("Квиз пройден", "Quiz complete")
          },
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = if (keyed > 0 && correctCount == keyed) CorrectGreen
          else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
          answers.clear()
          onStateChanged?.invoke(null)
        }) { Text(tr("Пройти ещё раз", "Try again")) }
      }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F, heightDp = 700)
@Composable
private fun QuizContentPreview() {
  MyApplicationTheme {
    QuizContent(
      questions = listOf(
        QuizQuestion(
          question = "Как общаться с ассистентом?",
          options = listOf("Обычными словами, как в переписке", "Только командами со слешем"),
          correctIndex = 0,
          explanation = "Пиши как человеку — агент сам поймёт, какой инструмент нужен.",
        ),
      ),
    )
  }
}
