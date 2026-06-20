package com.aiopenpocket.ui.roadmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.roadmap.components.QuizContent
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
  levelId: String,
  onFinished: () -> Unit,
) {
  val viewModel = metroViewModel<QuizViewModel>()
  val questions = LESSON_QUIZZES[levelId].orEmpty()

  // Nothing to ask — count the lesson done and leave immediately.
  if (questions.isEmpty()) {
    LaunchedEffect(levelId) { viewModel.finish(levelId, onFinished) }
    return
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Проверка") },
        actions = {
          TextButton(onClick = { viewModel.finish(levelId, onFinished) }) {
            Text("Пропустить")
          }
        },
      )
    },
    bottomBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
      ) {
        Button(
          onClick = { viewModel.finish(levelId, onFinished) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Завершить урок")
        }
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 24.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      Text(
        text = "Закрепим пройденное",
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(vertical = 16.dp),
      )
      QuizContent(questions = questions)
      Text(
        text = "Можно пропустить — урок всё равно засчитается.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
      )
    }
  }
}
