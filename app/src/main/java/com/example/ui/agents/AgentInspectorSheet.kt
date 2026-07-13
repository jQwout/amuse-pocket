package com.aiopenpocket.ui.agents

// @trace agent-inspector-6e41eb
// @trace user-agent-builder-848153

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.theme.GlassTokens
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import com.aiopenpocket.ui.util.formatRelativeDate
import java.util.Locale

/**
 * Read-mostly инспектор мини-агента: glass-лист поверх «Тулов» с двумя вкладками — Обзор
 * (стат-плитки, препромпт, активные heartbeat'ы, возможности, хуки-«на будущее») и Файлы (личная
 * песочница ЭТОГО агента, Task 6). Ничего не редактируется и не переключается — единственное
 * действие листа — «Открыть чат».
 *
 * Тонкая обёртка над [ModalBottomSheet]: весь видимый контент живёт в [AgentInspectorContent],
 * чтобы скриншот-тест мог рендерить его напрямую, в обход капризного под Robolectric
 * ModalBottomSheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentInspectorSheet(
  agent: AgentDefinition,
  state: AgentInspectorState,
  sheetState: SheetState,
  onDismiss: () -> Unit,
  onOpenChat: () -> Unit,
) {
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = Color.Transparent,
  ) {
    AgentInspectorContent(agent = agent, state = state, onOpenChat = onOpenChat)
  }
}

/**
 * Вкладка инспектора; `internal`, не `private`, — так скриншот-тест видит тип и может задать
 * стартовую вкладку через `initialTab` в [AgentInspectorContent] напрямую, без клика по чипу.
 */
internal enum class InspectorTab(val label: String) {
  Overview("Обзор"),
  Files("Файлы"),
}

/**
 * Всё видимое содержимое листа. Сам себе рисует [auroraBackground] и заполняет весь размер —
 * `ModalBottomSheet` открывается в отдельном Popup-слое и НЕ наследует аврору родительского
 * экрана, так что фон должен быть здесь, а не у вызывающей стороны.
 */
@Composable
internal fun AgentInspectorContent(
  agent: AgentDefinition,
  state: AgentInspectorState,
  onOpenChat: () -> Unit,
  initialTab: InspectorTab = InspectorTab.Overview,
) {
  var tab by remember { mutableStateOf(initialTab) }

  Box(modifier = Modifier.fillMaxSize().auroraBackground()) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(top = 28.dp, bottom = 40.dp),
    ) {
      InspectorHeader(agent = agent, onOpenChat = onOpenChat)
      Spacer(modifier = Modifier.height(20.dp))
      TabSwitch(selected = tab, onSelect = { tab = it })
      Spacer(modifier = Modifier.height(20.dp))
      when (tab) {
        InspectorTab.Overview -> OverviewTab(agent = agent, state = state)
        InspectorTab.Files -> FilesTab(agentId = agent.id, state = state)
      }
    }
  }
}

@Composable
private fun InspectorHeader(agent: AgentDefinition, onOpenChat: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(52.dp)
          .clip(CircleShape)
          .background(PurpleAccent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = iconFor(agent.iconKey),
          contentDescription = null,
          tint = PurpleAccent,
          modifier = Modifier.size(26.dp),
        )
      }
      Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
        Text(
          text = agent.name,
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = agent.id,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
      }
    }
    Spacer(modifier = Modifier.height(16.dp))
    Button(
      onClick = onOpenChat,
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp),
      shape = RoundedCornerShape(14.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = PurpleAccent,
        contentColor = PurpleAccentDark,
      ),
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.Chat,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(text = "Открыть чат", style = MaterialTheme.typography.titleMedium)
    }
  }
}

@Composable
private fun TabSwitch(selected: InspectorTab, onSelect: (InspectorTab) -> Unit, modifier: Modifier = Modifier) {
  Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    InspectorTab.entries.forEach { candidate ->
      TabChip(
        label = candidate.label,
        selected = candidate == selected,
        onClick = { onSelect(candidate) },
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
  GlassCard(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    onClick = onClick,
    tint = if (selected) PurpleAccent.copy(alpha = 0.22f) else GlassTokens.LightTint,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = if (selected) PurpleAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      textAlign = TextAlign.Center,
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 10.dp),
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(text = text, style = MaterialTheme.typography.labelLarge, color = PurpleAccent)
}

@Composable
private fun EmptyStateCard(text: String, modifier: Modifier = Modifier) {
  // Тот же вид, что EmptyHint в HomeScreen (file-private там же, поэтому не переиспользуем напрямую).
  GlassCard(modifier = modifier.fillMaxWidth()) {
    Text(
      text = text,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
      modifier = Modifier.padding(18.dp),
    )
  }
}

// ==================================================================================================
// Обзор: стат-плитки, препромпт, активные heartbeat'ы, возможности, хуки «на будущее».
// ==================================================================================================

@Composable
private fun OverviewTab(agent: AgentDefinition, state: AgentInspectorState, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
    StatGrid(agent = agent, state = state)
    PromptSection(agent = agent)
    HeartbeatSection(heartbeats = state.activeHeartbeats)
    CapabilitiesSection(agent = agent)
    HooksSection()
  }
}

@Composable
private fun StatGrid(agent: AgentDefinition, state: AgentInspectorState, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(label = "Тулов", value = agent.toolNames.size.toString(), modifier = Modifier.weight(1f))
      StatTile(
        label = "Навык",
        value = if ("load_skill" in agent.toolNames) "есть" else "—",
        modifier = Modifier.weight(1f),
      )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(
        label = "Heartbeat",
        value = state.activeHeartbeats.size.toString(),
        modifier = Modifier.weight(1f),
      )
      StatTile(
        label = "Области состояния",
        value = agent.stateKeyPrefixes.size.toString(),
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      Text(text = value, style = MaterialTheme.typography.headlineSmall, color = PurpleAccent)
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
      )
    }
  }
}

@Composable
private fun PromptSection(agent: AgentDefinition, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Препромпт")
    Spacer(modifier = Modifier.height(8.dp))
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      Text(
        text = agent.systemPrompt,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 180.dp)
          .verticalScroll(rememberScrollState())
          .padding(14.dp),
      )
    }
  }
}

@Composable
private fun HeartbeatSection(heartbeats: List<HeartbeatEntity>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Heartbeat")
    Spacer(modifier = Modifier.height(8.dp))
    if (heartbeats.isEmpty()) {
      EmptyStateCard("нет активных напоминаний")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        heartbeats.forEach { hb -> HeartbeatRow(hb) }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeartbeatRow(hb: HeartbeatEntity, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
      Text(
        text = hb.title,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(4.dp))
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        MetaText(formatInterval(hb))
        MetaText(hb.lastRunAt?.let { formatRelativeDate(it) } ?: "—")
        MetaText(text = formatHeartbeatStatus(hb), color = PurpleAccent)
      }
    }
  }
}

@Composable
private fun MetaText(text: String, color: Color? = null) {
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = color ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
  )
}

/**
 * «каждые N мин» для периодических, «разово» для one_time. Проще, чем маппинг в
 * ui.chat.HeartbeatCardFormat.formatSchedule (та вкладывает обратный отсчёт до срабатывания) —
 * здесь только факт периодичности, без отсчёта, как явно требует бриф инспектора.
 */
private fun formatInterval(hb: HeartbeatEntity): String =
  hb.intervalMinutes?.let { "каждые $it мин" } ?: "разово"

// Тот же маппинг статусов, что ui.chat.HeartbeatCardFormat.formatStatus (карточка reminder в
// чате) — не импортируем напрямую из чужого UI-пакета ради 4 строк; статусы HeartbeatEntity
// фиксированы (scheduled/fired/cancelled/error), дрейф между копиями маловероятен.
private fun formatHeartbeatStatus(hb: HeartbeatEntity): String = when (hb.status) {
  "fired" -> "Сработало"
  "cancelled" -> "Отменено"
  "error" -> "Ошибка"
  else -> "Ждёт"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilitiesSection(agent: AgentDefinition, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Возможности")
    Spacer(modifier = Modifier.height(8.dp))
    val labels = remember(agent.toolNames) { capabilityLabels(agent.toolNames) }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      labels.forEach { CapabilityChip(it) }
    }
  }
}

@Composable
private fun CapabilityChip(label: String) {
  // Стиль-двойник CapabilityChip из AgentsScreen.kt (там private, не поднимаем — бриф просит
  // поднять только capabilityLabels/iconFor).
  Text(
    text = label,
    style = MaterialTheme.typography.labelSmall,
    color = PurpleAccent,
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(PurpleAccent.copy(alpha = 0.12f))
      .padding(horizontal = 8.dp, vertical = 4.dp),
  )
}

@Composable
private fun HooksSection(modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Хуки (на будущее)")
    Spacer(modifier = Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      HookRow(name = "on_tool_call", description = "Логирование вызовов инструментов", active = true)
      HookRow(name = "pre_message", description = "Перед отправкой сообщения агенту", active = false)
      HookRow(name = "post_message", description = "После ответа агента", active = false)
      HookRow(name = "on_error", description = "При ошибке инструмента", active = false)
    }
  }
}

@Composable
private fun HookRow(name: String, description: String, active: Boolean, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = name,
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
      }
      // Статичный индикатор — read-mostly: НЕ Switch, кликов не принимает.
      HookStatusBadge(active = active)
    }
  }
}

@Composable
private fun HookStatusBadge(active: Boolean, modifier: Modifier = Modifier) {
  val label = if (active) "активен" else "скоро"
  val color = if (active) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
  }
}

// ==================================================================================================
// Файлы: личная песочница ЭТОГО агента, filesDir/agents/{agentId} (Task 6) — не общая.
// ==================================================================================================

@Composable
private fun FilesTab(agentId: String, state: AgentInspectorState, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = "Песочница агента (filesDir/agents/$agentId)",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
    if (state.files.isEmpty()) {
      EmptyStateCard("файлов нет")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.files.forEach { file -> FileRow(file) }
      }
    }
  }
}

@Composable
private fun FileRow(file: AgentFile, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
        contentDescription = null,
        tint = PurpleAccent,
        modifier = Modifier.size(20.dp),
      )
      Column(
        modifier = Modifier
          .weight(1f)
          .padding(horizontal = 12.dp),
      ) {
        Text(
          text = file.name,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = formatRelativeDate(file.lastModified),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
      }
      Text(
        text = formatBytes(file.sizeBytes),
        style = MaterialTheme.typography.labelMedium,
        color = PurpleAccent,
      )
    }
  }
}

/** B/KB/MB — файлы агент-песочницы редко превышают несколько МБ, ГБ не ждём. */
private fun formatBytes(bytes: Long): String = when {
  bytes < 1_024 -> "$bytes B"
  bytes < 1_024 * 1_024 -> "%.1f KB".format(Locale.US, bytes / 1_024.0)
  else -> "%.1f MB".format(Locale.US, bytes / (1_024.0 * 1_024.0))
}
