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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Videocam
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
import com.aiopenpocket.i18n.tr
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
              contentDescription = tr("Назад", "Back"),
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
          text = tr("Библиотека", "Library"),
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = tr(
            "Короткие уроки-концепции. Открой любой — он пройдёт прямо в чате, шаг за шагом.",
            "Short concept lessons. Open any of them — it runs right in the chat, step by step.",
          ),
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
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .clickable(onClick = onClick)
      .padding(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(44.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.onPrimary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = lessonIcon(lesson.iconKey),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )
    }
    Column(
      modifier = Modifier
        .weight(1f)
        .padding(horizontal = 14.dp),
    ) {
      Text(
        text = lesson.displayTitle(),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = lesson.displayDescription(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
    }
    if (completed) {
      Icon(
        imageVector = Icons.Default.Check,
        contentDescription = tr("Пройдено", "Completed"),
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}

/**
 * Английские тексты карточек уроков, которых нет на пути (остальные берутся из [ROADMAP_LEVELS]).
 * Только данные: язык выбирается в [displayTitle]/[displayDescription] в момент показа.
 */
private val LIBRARY_ONLY_EN: Map<String, Pair<String, String>> = mapOf(
  "rag" to Pair(
    "RAG: answers from your own data",
    "Why the model doesn't know your files, and how finding the right pieces before it answers fixes that.",
  ),
)

/**
 * Заголовок карточки на языке интерфейса. По-русски — из JSON урока, как и раньше. По-английски
 * переведены пока не все уроки, поэтому английский берём из каталога пути — он есть для каждого урока.
 */
private fun LibraryLesson.displayTitle(): String {
  val en = ROADMAP_LEVELS.find { it.id == id }?.titleEn ?: LIBRARY_ONLY_EN[id]?.first ?: return title
  return tr(title, en)
}

/** Описание карточки на языке интерфейса — по тому же правилу, что [displayTitle]. */
private fun LibraryLesson.displayDescription(): String {
  val en = ROADMAP_LEVELS.find { it.id == id }?.descriptionEn ?: LIBRARY_ONLY_EN[id]?.second ?: return description
  return tr(description, en)
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
  "manage_search", "search" -> Icons.AutoMirrored.Filled.ManageSearch
  "layers", "context" -> Icons.Default.Layers
  "auto_awesome", "reflect" -> Icons.Default.AutoAwesome
  "book" -> Icons.AutoMirrored.Filled.MenuBook
  "videocam", "vision" -> Icons.Default.Videocam
  else -> Icons.AutoMirrored.Filled.Article
}
