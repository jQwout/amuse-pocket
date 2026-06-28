package com.aiopenpocket.ui.roadmap

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun LibraryScreen(
  onNavigateBack: () -> Unit,
  onOpenLessonChat: (String) -> Unit,
) {
  val viewModel: LibraryViewModel = metroViewModel()
  val lessons by viewModel.lessons.collectAsState()
  val completed by viewModel.completed.collectAsState()

  Scaffold { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 20.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          IconButton(onClick = onNavigateBack) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Назад",
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
          Text(
            text = "AI Academy",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
          )
        }
      }

      item {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          text = "Библиотека",
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Короткие уроки-концепции. Открой любой — он пройдёт прямо в чате, шаг за шагом.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(28.dp))
      }

      items(lessons, key = { it.id }) { lesson ->
        LibraryCard(
          lesson = lesson,
          completed = lesson.id in completed,
          onClick = { viewModel.openLesson(lesson.id, onOpenLessonChat) },
        )
        Spacer(modifier = Modifier.height(12.dp))
      }

      item { Spacer(modifier = Modifier.height(16.dp)) }
    }
  }
}

@Composable
private fun LibraryCard(
  lesson: LibraryLesson,
  completed: Boolean,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CardDark)
      .clickable(onClick = onClick)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(PurpleAccentDark),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = lessonIcon(lesson.iconKey),
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 14.dp),
    ) {
      Text(
        text = lesson.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = lesson.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
    }
    if (completed) {
      Icon(
        imageVector = Icons.Default.Check,
        contentDescription = "Пройдено",
        tint = PurpleAccent,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

private fun lessonIcon(key: String): ImageVector = when (key) {
  "flag" -> Icons.Default.Flag
  "checklist" -> Icons.Default.Checklist
  "loop", "cycle" -> Icons.Default.Loop
  "bolt", "speed" -> Icons.Default.Bolt
  "hub", "network" -> Icons.Default.Hub
  "tree", "routing" -> Icons.Default.AccountTree
  "shield", "privacy" -> Icons.Default.Shield
  "memory" -> Icons.Default.Memory
  "extension" -> Icons.Default.Extension
  "build", "tools" -> Icons.Default.Build
  "edit", "chat" -> Icons.Default.EditNote
  "target" -> Icons.Default.TrackChanges
  "thermostat", "temperature" -> Icons.Default.Thermostat
  "manage_search", "search" -> Icons.Default.ManageSearch
  "book" -> Icons.Default.MenuBook
  else -> Icons.Default.Article
}
