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
import com.aiopenpocket.ui.roadmap.LESSON_QUIZZES
import com.aiopenpocket.ui.roadmap.QuizQuestion
import com.aiopenpocket.ui.theme.MyApplicationTheme
import kotlin.random.Random

private val CorrectGreen = Color(0xFF4CAF50)

/**
 * Детерминированно перемешанные индексы [0, size): seed считается из содержимого, поэтому порядок
 * одинаков на каждом показе — сохранённые ответы и подсветка не съезжают после рециклинга, поворота
 * или переоткрытия. Лечит «правильный ответ всегда первый» в авторских JSON, не трогая контент.
 * [avoidIdentity] — для match-карточки: исходный порядок подсказывал бы все пары разом.
 */
internal fun stableShuffledIndices(size: Int, seed: Int, avoidIdentity: Boolean = false): List<Int> {
  val order = (0 until size).shuffled(Random(seed))
  val isIdentity = order.withIndex().all { (i, v) -> i == v }
  return if (avoidIdentity && size > 1 && isIdentity) order.drop(1) + order.first() else order
}

/** Seed перемешивания вариантов вопроса — стабилен, пока не меняется сам вопрос. */
private fun QuizQuestion.shuffleSeed(): Int =
  question.hashCode() * 31 + options.fold(0) { acc, o -> acc * 31 + o.hashCode() }

/**
 * Renders the lesson quiz: a list of multiple-choice questions, each revealing
 * correct/incorrect feedback once answered. Non-blocking — purely a self-check.
 * Hosted by both the standalone QuizScreen and the onboarding quiz step.
 */
@Composable
fun QuizContent(
  questions: List<QuizQuestion>,
  modifier: Modifier = Modifier,
  /** Fires once every question has an answer — used by the in-chat lesson quiz to advance. */
  onAllAnswered: (() -> Unit)? = null,
) {
  // questionIndex -> selected option index. rememberSaveable, чтобы ответы переживали рециклинг
  // элемента в LazyColumn, поворот и process death (иначе пройденный квиз перерисовывается пустым).
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
  ) { mutableStateMapOf<Int, Int>() }

  // onAllAnswered должен сработать РОВНО раз за жизнь сохранённого состояния. rememberSaveable
  // восстанавливает уже полный map после поворота/process death/рециклинга элемента в LazyColumn —
  // без этого флага LaunchedEffect дёрнул бы колбэк повторно и промотал бы урок на лишний шаг.
  val fired = rememberSaveable { mutableStateOf(false) }
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
        text = "Вопрос ${qIndex + 1} из ${questions.size}",
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

      // Варианты показываем в перемешанном (но стабильном) порядке; в answers кладём ИСХОДНЫЙ
      // индекс варианта — сверка с correctIndex и восстановление сохранённых ответов не зависят
      // от порядка показа.
      val displayOrder = remember(question) {
        stableShuffledIndices(question.options.size, question.shuffleSeed())
      }
      displayOrder.forEach { i ->
        val option = question.options[i]
        val answered = selected != null
        // correctIndex < 0 — правильный вариант не задан/битый (см. f646e64): не подсвечиваем
        // ничего как «верное/неверное», чтобы не вводить в заблуждение ложным ключом.
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
        // Полупрозрачная подложка отделяет вариант от ауроры (иначе тёмное на тёмном сливается);
        // у отвеченных верный/неверный дополнительно подсвечены цветным wash'ем — усиливает фидбек.
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
            .clickable(enabled = selected == null) { answers[qIndex] = i }
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

    // Итог: счёт по вопросам с ключом + пересдача. «Пройти ещё раз» чистит только ответы:
    // fired не трогаем, поэтому урок не продвинется второй раз — пересдача чисто для самопроверки.
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
          text = if (keyed > 0) "Верно: $correctCount из $keyed" else "Квиз пройден",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          color = if (keyed > 0 && correctCount == keyed) CorrectGreen
          else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { answers.clear() }) { Text("Пройти ещё раз") }
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
