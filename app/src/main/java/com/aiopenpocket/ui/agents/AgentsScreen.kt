package com.aiopenpocket.ui.agents

// @trace multi-agent-orchestration-27b8e3
// @trace user-agent-builder-848153

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Roster screen (Task 21): tapping a card navigates to the full-screen agent detail (`agent/{id}`,
 * [com.aiopenpocket.ui.agents.detail.AgentDetailRoute]) — the old read-only glass bottom-sheet
 * inspector is gone, along with "Открыть чат" living only inside that sheet. Opening a chat is now
 * the detail screen's own header action. [onCreateAgent] (the FAB) starts a brand-new draft in the
 * Task 20 builder (`agent_builder`).
 */
@Composable
fun AgentsScreen(
  onOpenAgent: (String) -> Unit,
  onCreateAgent: () -> Unit,
  onNavigateBack: () -> Unit,
) {
  val viewModel: AgentsViewModel = metroViewModel()
  val roster by viewModel.roster.collectAsState()
  val glassState = rememberGlassState()

  AcademyCollapsingScaffold(
    title = "Агенты",
    glassState = glassState,
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Назад",
        )
      }
    },
    floatingActionButton = {
      ExtendedFloatingActionButton(
        onClick = onCreateAgent,
        icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
        text = { Text("Создать агента") },
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
      )
    },
  ) { padding ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .padding(horizontal = 20.dp),
      contentPadding = PaddingValues(bottom = 96.dp),
    ) {
      item {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "Агенты-специалисты со своими инструментами. Открой нужного — и опиши задачу.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(20.dp))
      }

      if (roster.presets.isNotEmpty()) {
        item {
          RosterSectionLabel("Шаблоны")
          Spacer(modifier = Modifier.height(10.dp))
        }
        items(roster.presets, key = { it.id }) { agent ->
          AgentCard(agent = agent, onClick = { onOpenAgent(agent.id) })
          Spacer(modifier = Modifier.height(12.dp))
        }
      }

      if (roster.userAgents.isNotEmpty()) {
        item {
          Spacer(modifier = Modifier.height(8.dp))
          RosterSectionLabel("Мои агенты")
          Spacer(modifier = Modifier.height(10.dp))
        }
        items(roster.userAgents, key = { it.id }) { agent ->
          AgentCard(agent = agent, onClick = { onOpenAgent(agent.id) })
          Spacer(modifier = Modifier.height(12.dp))
        }
      }
    }
  }
}

@Composable
private fun RosterSectionLabel(text: String) {
  Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentCard(agent: AgentDefinition, onClick: () -> Unit) {
  GlassCard(
    modifier = Modifier.fillMaxWidth(),
    shape = RoundedCornerShape(16.dp),
    onClick = onClick,
  ) {
    // GlassCard's own Column has no contentPadding slot in the frozen API, so this inner Column
    // is the one place that owns the 16dp inset the old MaterialTheme.colorScheme.surfaceVariant Column used to apply directly
    // (same pattern as Home's CourseCard).
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        Box(
          modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = iconFor(agent.iconKey),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = agent.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = agent.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
      }

      // «Под капотом»: что у агента в распоряжении — инструменты и навыки (его скоуп). Делает
      // карточку информативной: видно, на что агент способен, ещё до открытия чата.
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = "Умеет",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
      )
      Spacer(modifier = Modifier.height(6.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val labels = remember(agent.toolNames) { capabilityLabels(agent.toolNames) }
        labels.forEach { CapabilityChip(it) }
      }
    }
  }
}

@Composable
private fun CapabilityChip(label: String) {
  Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
      .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

/** Человекочитаемые ярлыки инструментов агента — его «оснащение». Неизвестные имена пропускаем. */
internal fun capabilityLabels(toolNames: Set<String>): List<String> =
  toolNames.mapNotNull { tool ->
    when (tool) {
      "memory_tool" -> "Память"
      "render_card" -> "Карточки"
      "load_skill" -> "Навыки"
      "sms" -> "СМС"
      "email" -> "Почта"
      "alarm" -> "Будильник"
      "work_manager" -> "Фоновые задачи"
      "calendar" -> "Календарь"
      "file_tool" -> "Файлы"
      "srs_tool" -> "Словарь"
      else -> null
    }
  }.distinct()

internal fun iconFor(key: String): ImageVector = when (key) {
  "wallet" -> Icons.Default.AccountBalanceWallet
  "translate" -> Icons.Default.Translate
  "checklist" -> Icons.Default.Checklist
  "alarm" -> Icons.Default.Alarm
  "restaurant" -> Icons.Default.Restaurant
  "book" -> Icons.AutoMirrored.Filled.MenuBook
  else -> Icons.Default.SmartToy
}

// ==================================================================================================
// Превью экрана Agents (скриншот-тесты). Тот же визуальный каркас (аврора авто-фон каркаса через
// glassState, GlassCard на карточках), что и в бою, собранный из той же file-private карточки
// [AgentCard], но с презетами из [MiniAgentCatalog] (адаптированными в [AgentDefinition] через
// [toDefinition]) вместо ViewModel — [AgentsScreen] тянет [AgentsViewModel] через metroViewModel(),
// которому в Robolectric/@Preview неоткуда взять DI-граф. [userAgents] пуст по умолчанию — как и в
// бою, секция «Мои агенты» просто не рендерится, пока пуста.
// ==================================================================================================

@Composable
internal fun AgentsPreviewScaffold(
  presets: List<AgentDefinition> = MiniAgentCatalog.all.map { it.toDefinition() },
  userAgents: List<AgentDefinition> = emptyList(),
) {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    val glassState = rememberGlassState()
    AcademyCollapsingScaffold(
      title = "Агенты",
      glassState = glassState,
      navigationIcon = {
        IconButton(onClick = {}) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Назад",
          )
        }
      },
      floatingActionButton = {
        ExtendedFloatingActionButton(
          onClick = {},
          icon = { Icon(imageVector = Icons.Default.Add, contentDescription = null) },
          text = { Text("Создать агента") },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        )
      },
    ) { padding ->
      LazyColumn(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
      ) {
        item {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Агенты-специалисты со своими инструментами. Открой нужного — и опиши задачу.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
          Spacer(modifier = Modifier.height(20.dp))
        }

        if (presets.isNotEmpty()) {
          item {
            RosterSectionLabel("Шаблоны")
            Spacer(modifier = Modifier.height(10.dp))
          }
          items(presets, key = { it.id }) { agent ->
            AgentCard(agent = agent, onClick = {})
            Spacer(modifier = Modifier.height(12.dp))
          }
        }

        if (userAgents.isNotEmpty()) {
          item {
            Spacer(modifier = Modifier.height(8.dp))
            RosterSectionLabel("Мои агенты")
            Spacer(modifier = Modifier.height(10.dp))
          }
          items(userAgents, key = { it.id }) { agent ->
            AgentCard(agent = agent, onClick = {})
            Spacer(modifier = Modifier.height(12.dp))
          }
        }
      }
    }
  }
}
