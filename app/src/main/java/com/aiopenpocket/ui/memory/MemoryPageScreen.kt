package com.aiopenpocket.ui.memory

// @trace agent-memory-05e545

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.glassCardSurface
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.metroViewModel

@Composable
fun MemoryPageScreen(
  conversationId: String,
  onNavigateBack: () -> Unit,
  onOpenChat: (String) -> Unit,
) {
  val viewModel: MemoryPageViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(conversationId) { viewModel.load(conversationId) }

  MemoryPageContent(
    state = state,
    onNavigateBack = onNavigateBack,
    onOpenChat = { onOpenChat(conversationId) },
  )
}

@Composable
private fun MemoryPageContent(
  state: MemoryPageState,
  onNavigateBack: () -> Unit,
  onOpenChat: () -> Unit,
) {
  // The glass header on the aurora, like the memory graph that opens this page.
  AcademyCollapsingScaffold(
    title = state.title.ifBlank { tr("Память", "Memory") },
    glassState = rememberGlassState(),
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Назад", "Back"))
      }
    },
    actions = {
      IconButton(onClick = onOpenChat) {
        Icon(Icons.Default.Edit, contentDescription = tr("Открыть чат", "Open chat"))
      }
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      contentPadding = PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = padding.calculateTopPadding() + 8.dp,
        bottom = padding.calculateBottomPadding() + 16.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (!state.summary.isNullOrBlank()) {
        item { SummaryCard(state.summary) }
      }
      if (state.tags.isNotEmpty()) {
        item {
          Text(
            state.tags.joinToString(" · ") { "#$it" },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }
      item { Text(tr("Сообщения", "Messages"), style = MaterialTheme.typography.titleSmall) }
      items(state.messages, key = { it.id }) { message -> MessageRow(message) }
    }
  }
}

@Composable
private fun SummaryCard(summary: String) {
  Column(modifier = Modifier.fillMaxWidth().glassCardSurface(RoundedCornerShape(16.dp))) {
    Column(modifier = Modifier.padding(16.dp)) {
      Text(
        tr("Краткое содержание", "Summary"),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(summary, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
private fun MessageRow(message: Message) {
  val isUser = message.role == "user"
  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
    Column {
      Text(
        if (isUser) tr("Вы", "You") else tr("Агент", "Agent"),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
      )
      Text(message.content, style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun MemoryPagePreview() {
  MyApplicationTheme {
    MemoryPageContent(
      state = MemoryPageState(
        title = "Идеи проекта",
        summary = "Обсудили MVP и дорожную карту.",
        tags = listOf("работа", "идеи"),
        messages = listOf(
          Message("1", "c", "user", "Что у нас в работе?", null, createdAt = 0L),
          Message("2", "c", "assistant", "Сейчас приоритет — MVP к понедельнику.", null, createdAt = 0L),
        ),
      ),
      onNavigateBack = {},
      onOpenChat = {},
    )
  }
}
