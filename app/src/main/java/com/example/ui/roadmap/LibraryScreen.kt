package com.aiopenpocket.ui.roadmap

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.PurpleAccent

private data class LibraryItem(
  val title: String,
  val description: String,
  val category: String,
)

private val LIBRARY_ITEMS = listOf(
  LibraryItem(
    title = "Основы промптинга",
    description = "Как формулировать запросы, чтобы агент понимал задачу с первого раза.",
    category = "Основы",
  ),
  LibraryItem(
    title = "Работа с файлами",
    description = "Создание, чтение и поиск заметок в локальном хранилище.",
    category = "Инструменты",
  ),
  LibraryItem(
    title = "Память и навыки",
    description = "Факты, эпизоды и возможности агента — что он помнит и что умеет.",
    category = "Память",
  ),
  LibraryItem(
    title = "Tool Trace",
    description = "Как читать трассировку инструментов и понимать решения агента.",
    category = "Продвинутое",
  ),
)

@Composable
fun LibraryScreen(
  onNavigateBack: () -> Unit,
) {
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
          text = "Справочные материалы и шпаргалки по работе с агентом.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(28.dp))
      }

      items(LIBRARY_ITEMS) { item ->
        LibraryCard(item = item)
        Spacer(modifier = Modifier.height(12.dp))
      }

      item { Spacer(modifier = Modifier.height(16.dp)) }
    }
  }
}

@Composable
private fun LibraryCard(item: LibraryItem) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(CardDark)
      .padding(16.dp),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Icon(
        imageVector = Icons.Default.Article,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(20.dp),
      )
      Text(
        text = item.category.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = PurpleAccent,
      )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = item.title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = item.description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
  }
}
