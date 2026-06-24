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
import androidx.compose.material.icons.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.AcademyBottomNav
import com.aiopenpocket.ui.components.AcademyHeader
import com.aiopenpocket.ui.components.AcademyTab
import com.aiopenpocket.ui.theme.CardDark
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.PurpleAccent

private data class LibraryItem(
  val title: String,
  val description: String,
  val category: String,
)

private val LIBRARY_ITEMS = listOf(
  LibraryItem(
    "Основы промптинга",
    "Как формулировать запросы, чтобы агент понимал задачу с первого раза.",
    "Основы",
  ),
  LibraryItem(
    "Работа с файлами",
    "Создание, чтение и поиск заметок в локальном хранилище.",
    "Инструменты",
  ),
  LibraryItem(
    "Память и навыки",
    "Факты, эпизоды и возможности агента — что он помнит и что умеет.",
    "Память",
  ),
  LibraryItem(
    "Tool Trace",
    "Как читать трассировку инструментов и понимать решения агента.",
    "Продвинутое",
  ),
)

@Composable
fun LibraryScreen(
  onNavigateToChats: () -> Unit,
  onNavigateToPath: () -> Unit,
  onNavigateToSettings: () -> Unit,
) {
  Scaffold(
    bottomBar = {
      AcademyBottomNav(
        selectedTab = AcademyTab.Library,
        onTabSelected = { tab ->
          when (tab) {
            AcademyTab.Chats -> onNavigateToChats()
            AcademyTab.Path -> onNavigateToPath()
            AcademyTab.Library -> Unit
          }
        },
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 20.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(16.dp))
        AcademyHeader(onNavigateToSettings = onNavigateToSettings)
      }
      item {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
          "Библиотека",
          style = MaterialTheme.typography.headlineLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          "Справочные материалы и шпаргалки по работе с агентом.",
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
        Icons.Default.Article,
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
      item.title,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      item.description,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun LibraryScreenPreview() {
  MyApplicationTheme {
    LibraryScreen(
      onNavigateToChats = {},
      onNavigateToPath = {},
      onNavigateToSettings = {},
    )
  }
}
