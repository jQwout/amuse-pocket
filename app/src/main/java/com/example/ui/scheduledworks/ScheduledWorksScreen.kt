package com.aiopenpocket.ui.scheduledworks

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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.zacsweers.metrox.viewmodel.metroViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledWorksScreen(
  onNavigateBack: () -> Unit,
) {
  val viewModel = metroViewModel<ScheduledWorksViewModel>()
  val state by viewModel.state.collectAsState()

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
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      when {
        state.isLoading -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Загрузка...", style = MaterialTheme.typography.bodyLarge)
          }
        }
        state.works.isEmpty() -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нет активных задач", style = MaterialTheme.typography.bodyLarge)
          }
        }
        else -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            items(state.works, key = { it.id }) { work ->
              WorkItemCard(
                work = work,
                onCancel = { viewModel.cancelWork(work.id) },
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun WorkItemCard(
  work: ScheduledWorkItem,
  onCancel: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = work.title.ifBlank { "Без названия" },
          style = MaterialTheme.typography.titleMedium,
        )
        TypeBadge(type = work.type)
      }

      Spacer(modifier = Modifier.height(4.dp))

      if (work.message.isNotBlank()) {
        Text(
          text = work.message,
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
          Text(
            text = "Состояние: ${work.state}",
            style = MaterialTheme.typography.labelMedium,
          )
          val dateStr = if (work.nextScheduleTimeMillis > 0) {
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
              .format(Date(work.nextScheduleTimeMillis))
          } else "—"
          Text(
            text = "Следующий запуск: $dateStr",
            style = MaterialTheme.typography.labelMedium,
          )
        }
        TextButton(onClick = onCancel) {
          Text("Отменить")
        }
      }
    }
  }
}

@Composable
private fun TypeBadge(type: String) {
  val label = when (type) {
    "one_time" -> "Разовая"
    "periodic" -> "Периодическая"
    else -> type
  }
  val color = when (type) {
    "one_time" -> MaterialTheme.colorScheme.primaryContainer
    "periodic" -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
  val textColor = when (type) {
    "one_time" -> MaterialTheme.colorScheme.onPrimaryContainer
    "periodic" -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onSurfaceVariant
  }
  androidx.compose.material3.Surface(
    color = color,
    shape = MaterialTheme.shapes.small,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = textColor,
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
    )
  }
}
