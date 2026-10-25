package com.aiopenpocket.ui.scheduledworks

// @trace background-tasks-15a34a

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.aiopenpocket.agent.tools.WorkManagerTool
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.util.formatDateTime
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledWorksScreen(onNavigateBack: () -> Unit) {
  val viewModel = metroViewModel<ScheduledWorksViewModel>()
  val state by viewModel.state.collectAsState()
  ScheduledWorksContent(
    works = state.works,
    isLoading = state.isLoading,
    onNavigateBack = onNavigateBack,
    onCancel = viewModel::cancelWork,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduledWorksContent(
  works: List<ScheduledWorkItem>,
  isLoading: Boolean,
  onNavigateBack: () -> Unit,
  onCancel: (ScheduledWorkItem) -> Unit,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Запланированные задачи") },
        navigationIcon = {
          IconButton(onClick = onNavigateBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
          }
        },
      )
    },
  ) { padding ->
    Box(modifier = Modifier.fillMaxSize().padding(padding)) {
      when {
        isLoading -> CenteredText("Загрузка...")
        works.isEmpty() -> CenteredText("Нет активных задач")
        else -> LazyColumn(
          modifier = Modifier.fillMaxSize(),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          items(works, key = { it.id }) { work ->
            WorkItemCard(work = work, onCancel = { onCancel(work) })
          }
        }
      }
    }
  }
}

@Composable
private fun CenteredText(text: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
private fun WorkItemCard(work: ScheduledWorkItem, onCancel: () -> Unit) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(work.title.ifBlank { "Без названия" }, style = MaterialTheme.typography.titleMedium)
        TypeBadge(type = work.type)
      }
      Spacer(modifier = Modifier.height(4.dp))
      if (work.message.isNotBlank()) {
        Text(
          work.message,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text("Состояние: ${work.state.label()}", style = MaterialTheme.typography.labelMedium)
          val dateStr = work.nextScheduleTimeMillis?.let(::formatDateTime) ?: "—"
          Text("Следующий запуск: $dateStr", style = MaterialTheme.typography.labelMedium)
        }
        TextButton(onClick = onCancel) { Text("Отменить") }
      }
    }
  }
}

@Composable
private fun TypeBadge(type: String) {
  val (label, bg, fg) = when (type) {
    WorkManagerTool.TAG_ONE_TIME -> Triple(
      "Разовая",
      MaterialTheme.colorScheme.primaryContainer,
      MaterialTheme.colorScheme.onPrimaryContainer,
    )
    WorkManagerTool.TAG_PERIODIC -> Triple(
      "Периодическая",
      MaterialTheme.colorScheme.secondaryContainer,
      MaterialTheme.colorScheme.onSecondaryContainer,
    )
    else -> Triple(
      type,
      MaterialTheme.colorScheme.surfaceVariant,
      MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
  Surface(color = bg, shape = MaterialTheme.shapes.small) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = fg,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ScheduledWorksPreview() {
  MyApplicationTheme {
    ScheduledWorksContent(
      works = listOf(
        ScheduledWorkItem(
          "1", "a", "Купить кофе", "Не забудь зайти в кофейню",
          WorkInfo.State.ENQUEUED, WorkManagerTool.TAG_ONE_TIME, 1_700_000_000_000L,
        ),
        ScheduledWorkItem(
          "2", "b", "Утренний чек-ин", "",
          WorkInfo.State.RUNNING, WorkManagerTool.TAG_PERIODIC, null,
        ),
      ),
      isLoading = false,
      onNavigateBack = {},
      onCancel = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun ScheduledWorksEmptyPreview() {
  MyApplicationTheme {
    ScheduledWorksContent(
      works = emptyList(),
      isLoading = false,
      onNavigateBack = {},
      onCancel = {},
    )
  }
}
