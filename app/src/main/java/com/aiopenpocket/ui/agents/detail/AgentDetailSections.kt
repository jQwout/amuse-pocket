package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
//
// Sections of the agent inspector screen (header, actions, tiles, prompt, plans, tools, skills,
// heartbeat, hooks, files) with their rows/badges. Moved out of AgentDetailScreen.kt — the screen only
// assembles them into a scroll; every section is stateless and takes its data as a parameter. Same
// package, so no cross-imports: the sections the screen calls changed visibility from private to internal.

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRunEntity
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.components.hookActionLabel
import com.aiopenpocket.ui.agents.builder.components.hookConditionSummary
import com.aiopenpocket.ui.agents.builder.components.hookEventLabel
import com.aiopenpocket.ui.agents.builder.components.riskColor
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.plan.PlanStatusBadge
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.aiopenpocket.ui.util.formatRelativeDate
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionsRow(
  state: AgentDetailState,
  onDuplicate: () -> Unit,
  onEdit: () -> Unit,
  onToggleEnabled: () -> Unit,
  onArchive: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    if (state.canDuplicate) {
      ActionChip(label = tr("Дублировать", "Duplicate"), onClick = onDuplicate, modifier = Modifier.testTag("agent_detail_action_duplicate"))
    }
    if (state.canEdit) {
      ActionChip(label = tr("Изменить", "Edit"), onClick = onEdit, modifier = Modifier.testTag("agent_detail_action_edit"))
    }
    if (state.canToggleEnabled) {
      ActionChip(
        label = if (state.isEnabled) tr("Отключить", "Disable") else tr("Включить", "Enable"),
        onClick = onToggleEnabled,
        modifier = Modifier.testTag("agent_detail_action_toggle_enabled"),
      )
    }
    if (state.canArchive) {
      ActionChip(label = tr("Архивировать", "Archive"), onClick = onArchive, modifier = Modifier.testTag("agent_detail_action_archive"))
    }
  }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier, shape = RoundedCornerShape(12.dp), onClick = onClick) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.primary,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
  }
}

@Composable
internal fun InvalidCapabilitiesBanner(modifier: Modifier = Modifier) {
  GlassCard(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(14.dp),
    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.error,
        modifier = Modifier.size(20.dp),
      )
      Spacer(modifier = Modifier.width(10.dp))
      Text(
        text = tr(
          "Некоторые возможности агента недоступны в этой версии приложения",
          "Some of this agent's capabilities aren't available in this version of the app",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
      )
    }
  }
}

/**
 * Summary counters. The tiles are NOT clickable: each used to have an empty onClick — the ripple
 * promised navigation, while the tap did nothing. The sections themselves follow below in order anyway.
 */
@Composable
internal fun StatTiles(
  state: AgentDetailState,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(label = tr("Инструменты", "Tools"), value = state.tools.size.toString(), modifier = Modifier.weight(1f))
      StatTile(label = tr("Навыки", "Skills"), value = state.skills.size.toString(), modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(label = tr("Напоминания", "Reminders"), value = state.activeHeartbeats.size.toString(), modifier = Modifier.weight(1f))
      StatTile(label = tr("Хуки", "Hooks"), value = state.hooks.size.toString(), modifier = Modifier.weight(1f))
    }
  }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      // As in the mockup: an uppercase gray label on top, a large value below.
      Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(text = value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    }
  }
}

/** Lines beyond which the preprompt defaults to a collapsible "Expand/Collapse" toggle (default expanded). */
private const val PROMPT_COLLAPSE_LINES = 40

@Composable
internal fun PromptSection(agent: AgentDefinition, modifier: Modifier = Modifier) {
  val lines = remember(agent.systemPrompt) { agent.systemPrompt.lines() }
  val isLong = lines.size > PROMPT_COLLAPSE_LINES
  var expanded by rememberSaveable(agent.id) { mutableStateOf(true) }

  Column(modifier = modifier.fillMaxWidth().testTag("agent_detail_prompt")) {
    AgentSectionHeader(icon = Icons.AutoMirrored.Filled.InsertDriveFile, label = tr("Препромпт", "System prompt"))
    Spacer(modifier = Modifier.height(8.dp))
    // A tap on the card expands/collapses it (a chevron in the mockup's style), not a separate button.
    // The behavior and the default (a long preprompt is expanded — the Task 21 brief) are kept; the
    // testTag moved from the button to the card, so the existing collapsed screenshot test clicks it.
    val cardModifier = if (isLong) {
      Modifier.fillMaxWidth().clickable { expanded = !expanded }.testTag("agent_detail_prompt_toggle")
    } else {
      Modifier.fillMaxWidth()
    }
    GlassCard(modifier = cardModifier, shape = RoundedCornerShape(16.dp)) {
      Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
        val displayText = if (isLong && !expanded) {
          lines.take(PROMPT_COLLAPSE_LINES).joinToString("\n") + "\n…"
        } else {
          agent.systemPrompt
        }
        Text(
          text = displayText,
          style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
          modifier = Modifier.weight(1f),
        )
        if (isLong) {
          Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (expanded) tr("Свернуть", "Collapse") else tr("Развернуть", "Expand"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
          )
        }
      }
    }
  }
}

@Composable
internal fun PlansSection(
  state: AgentDetailState,
  onOpenPlan: (String) -> Unit,
  onOpenPlanList: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().testTag("agent_detail_plans")) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      SectionLabel(tr("Планы", "Plans"))
      Spacer(modifier = Modifier.weight(1f))
      if (state.plans.isNotEmpty()) {
        TextButton(onClick = onOpenPlanList, modifier = Modifier.testTag("agent_detail_plans_see_all")) {
          Text(text = tr("Все планы", "All plans"), style = MaterialTheme.typography.labelMedium)
        }
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (state.plans.isEmpty()) {
      // An honest empty card — presets and USER agents that have not run a plan yet simply have no plans.
      EmptyStateCard(tr("у агента нет планов", "this agent has no plans"))
    } else {
      // A plain Row (like StatTiles above), not a FlowRow: three FIXED tiles, no wrapping needed —
      // RowScope.weight is unambiguously available here, unlike FlowRowScope.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlanCountTile(label = tr("Активные", "Active"), count = state.activePlansCount, modifier = Modifier.weight(1f).testTag("agent_detail_plans_active_count"))
        PlanCountTile(label = tr("На паузе", "Paused"), count = state.pausedPlansCount, modifier = Modifier.weight(1f).testTag("agent_detail_plans_paused_count"))
        PlanCountTile(label = tr("Завершённые", "Finished"), count = state.completedPlansCount, modifier = Modifier.weight(1f).testTag("agent_detail_plans_completed_count"))
      }
      Spacer(modifier = Modifier.height(8.dp))
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.plans.forEach { row -> PlanRow(row, onClick = { onOpenPlan(row.planId) }) }
      }
    }
  }
}

@Composable
private fun PlanCountTile(label: String, count: Int, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier, shape = RoundedCornerShape(16.dp)) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      Text(text = count.toString(), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
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
private fun PlanRow(row: AgentDetailPlanRow, onClick: () -> Unit, modifier: Modifier = Modifier) {
  GlassCard(
    modifier = modifier.fillMaxWidth().testTag("agent_detail_plan_${row.planId}"),
    shape = RoundedCornerShape(14.dp),
    onClick = onClick,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
        Text(
          text = row.objective,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = tr("${row.stepsCompleted}/${row.stepsTotal} шагов", "${row.stepsCompleted}/${row.stepsTotal} steps"),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
      PlanStatusBadge(row.status)
    }
  }
}

@Composable
internal fun ToolsSection(tools: List<AgentDetailToolRow>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    AgentSectionHeader(
      icon = Icons.Default.Bolt,
      label = tr("Инструменты", "Tools"),
      trailing = tools.size.takeIf { it > 0 }?.toString(),
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (tools.isEmpty()) {
      EmptyStateCard(tr("у агента нет инструментов", "this agent has no tools"))
    } else {
      // One container (in the mockup the tools sit in a single card); rows are separated by a thin line.
      GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        tools.forEachIndexed { i, row ->
          if (i > 0) {
            HorizontalDivider(
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
              modifier = Modifier.padding(horizontal = 14.dp),
            )
          }
          ToolRow(row)
        }
      }
    }
  }
}

@Composable
private fun ToolRow(row: AgentDetailToolRow, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .testTag("agent_detail_tool_${row.name}")
      .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = Icons.Default.Bolt,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(16.dp),
    )
    // The tool's name is the same as in the builder and in the "Can do" chips; the raw id stays only in the testTag.
    Text(
      text = CapabilityNames.tool(row.name),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f),
    )
    val risk = row.riskClass
    if (risk != null) RiskPill(risk) else UnavailablePill()
  }
}

/**
 * A pill in the mockup's style (the "Tools" frame). The color and the text are REAL (riskColor + a short
 * risk label), not the made-up allow/deny from the mockup: all of an agent's tools are allowed, so the
 * meaningful signal here is the risk class, not "ALLOWED" on every row.
 */
@Composable
private fun RiskPill(risk: RiskClass, modifier: Modifier = Modifier) {
  val color = riskColor(risk)
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 8.dp, vertical = 3.dp),
  ) {
    Text(text = shortRiskLabel(risk), style = MaterialTheme.typography.labelSmall, color = color)
  }
}

@Composable
private fun UnavailablePill(modifier: Modifier = Modifier) {
  val color = MaterialTheme.colorScheme.error
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(6.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 8.dp, vertical = 3.dp),
  ) {
    Text(text = tr("НЕДОСТУПЕН", "UNAVAILABLE"), style = MaterialTheme.typography.labelSmall, color = color)
  }
}

/** A short uppercase risk label for the pill (the full one is riskClassLabel, the builder shows it). */
private fun shortRiskLabel(risk: RiskClass): String = when (risk) {
  RiskClass.READ -> tr("ЧТЕНИЕ", "READ")
  RiskClass.WRITE -> tr("ЗАПИСЬ", "WRITE")
  RiskClass.DESTRUCTIVE -> tr("ОПАСНО", "DESTRUCTIVE")
  RiskClass.EXTERNAL -> tr("НАРУЖУ", "EXTERNAL")
}

/**
 * A section header in the mockup's style: an icon + an UPPERCASE gray label + an optional counter on the
 * right. Shared by the sections (so far applied to "Tools"; the others migrate in their own slices).
 */
@Composable
private fun AgentSectionHeader(
  icon: ImageVector,
  label: String,
  trailing: String? = null,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(15.dp),
    )
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = label.uppercase(),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (trailing != null) {
      Spacer(modifier = Modifier.weight(1f))
      Text(
        text = trailing,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun SkillsSection(skills: List<AgentDetailSkillRow>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel(tr("Навыки", "Skills"))
    Spacer(modifier = Modifier.height(8.dp))
    if (skills.isEmpty()) {
      EmptyStateCard(tr("у агента нет навыков", "this agent has no skills"))
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        skills.forEach { row -> SkillRow(row) }
      }
    }
  }
}

@Composable
private fun SkillRow(row: AgentDetailSkillRow, modifier: Modifier = Modifier) {
  GlassCard(
    modifier = modifier.fillMaxWidth().testTag("agent_detail_skill_${row.id}"),
    shape = RoundedCornerShape(14.dp),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
        Text(
          text = row.title,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = row.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (row.whenToUse.isNotBlank()) {
          Text(
            text = tr("Когда: ${row.whenToUse}", "When: ${row.whenToUse}"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
          )
        }
      }
      if (!row.available) UnavailablePill()
    }
  }
}

@Composable
internal fun HeartbeatSection(heartbeats: List<HeartbeatEntity>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    AgentSectionHeader(
      icon = Icons.Default.GraphicEq,
      // "Reminders", not "Heartbeat": that is what this tool is called both in the builder ("Background
      // reminders") and in the empty state below.
      label = tr("Напоминания", "Reminders"),
      trailing = heartbeats.size.takeIf { it > 0 }?.let { tr("активных: $it", "active: $it") },
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (heartbeats.isEmpty()) {
      EmptyStateCard(tr("нет активных напоминаний", "no active reminders"))
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        heartbeats.forEach { hb -> HeartbeatRow(hb) }
      }
    }
  }
}

@Composable
private fun HeartbeatRow(hb: HeartbeatEntity, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
    Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
      Text(
        text = hb.title,
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(modifier = Modifier.height(4.dp))
      // The schedule in lime with a clock icon, as in the mockup.
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
          imageVector = Icons.Default.Schedule,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(13.dp),
        )
        Text(
          text = formatInterval(hb),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      // The description = the agent's task (prompt) or the push text (message) — whichever there is.
      val desc = hb.prompt?.takeIf { it.isNotBlank() } ?: hb.message
      if (!desc.isNullOrBlank()) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = desc,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
    }
  }
}

/** "every N min" for periodic ones, "once" for one_time — the fact of periodicity, without a countdown. */
private fun formatInterval(hb: HeartbeatEntity): String =
  hb.intervalMinutes?.let { tr("каждые $it мин", "every $it min") } ?: tr("разово", "one-time")

@Composable
internal fun HooksSection(hooks: List<AgentDetailHookRow>, modifier: Modifier = Modifier) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  Column(modifier = modifier.fillMaxWidth()) {
    AgentSectionHeader(icon = Icons.Default.Bolt, label = tr("Хуки", "Hooks"))
    Spacer(modifier = Modifier.height(8.dp))
    if (hooks.isEmpty()) {
      // An honest empty card — presets and not-yet-configured USER agents simply have no hooks.
      EmptyStateCard(tr("у агента нет настроенных хуков", "this agent has no hooks set up"))
    } else {
      val enabled = hooks.count { it.hook.enabled }
      // A summary card as in the mockup (Overview); a tap expands the details read-only. The full hook
      // editor (toggles/adding) is in the builder, not here (the "restyle on real data" decision).
      GlassCard(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded }
          .testTag("agent_detail_hooks_toggle"),
        shape = RoundedCornerShape(14.dp),
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = tr("Действия на события", "Actions on events"),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = tr("$enabled из ${hooks.size} включены", "$enabled of ${hooks.size} enabled"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
              )
            }
            Icon(
              imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
              contentDescription = if (expanded) tr("Свернуть", "Collapse") else tr("Развернуть", "Expand"),
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          if (expanded) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              hooks.forEach { row -> HookRow(row) }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun HookRow(row: AgentDetailHookRow, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
      // The same captions as in the builder (HooksStep): event → action and condition. The hook's UUID and
      // raw enums like "BEFORE_TOOL → DENY" used to be shown here.
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "${hookEventLabel(row.hook.event)} → ${hookActionLabel(row.hook.actionType)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = tr("Условие: ", "Condition: ") + hookConditionSummary(row.hook),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
        HookEnabledBadge(row.hook.enabled)
      }
      if (row.recentRuns.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = tr("Последние срабатывания", "Recent runs"),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          row.recentRuns.forEach { run -> HookRunLine(run) }
        }
      }
    }
  }
}

@Composable
private fun HookRunLine(run: AgentHookRunEntity, modifier: Modifier = Modifier) {
  // status is HookActionType.name (what the hook did), errorMessage is the JSON parser's exception text.
  // Neither is shown as is: the action caption is the builder's, the error is in the interface language.
  val action = runCatching { HookActionType.valueOf(run.status) }.getOrNull()
  val text = buildString {
    append(formatRelativeDate(run.timestamp))
    append(" — ")
    append(action?.let(::hookActionLabel) ?: run.status)
    if (run.errorMessage != null) append(tr(": настройки хука не прочитались", ": couldn't read the hook settings"))
  }
  Text(
    text = text,
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    modifier = modifier,
  )
}

@Composable
private fun HookEnabledBadge(enabled: Boolean, modifier: Modifier = Modifier) {
  val label = if (enabled) tr("включён", "on") else tr("выключен", "off")
  val color = if (enabled) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
  }
}

@Composable
internal fun FilesSection(files: List<AgentFile>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel(tr("Файлы", "Files"))
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      // Without the internal path "filesDir/agents/<id>": it tells the user nothing.
      text = tr("Личная папка агента — другие агенты её не видят.", "The agent's private folder — other agents can't see it."),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (files.isEmpty()) {
      EmptyStateCard(tr("файлов нет", "no files"))
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file -> FileRow(file) }
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
        tint = MaterialTheme.colorScheme.primary,
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
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

/** B/KB/MB — files of an agent's sandbox rarely exceed a few MB; GB is not expected. */
private fun formatBytes(bytes: Long): String = when {
  bytes < 1_024 -> "$bytes B"
  bytes < 1_024 * 1_024 -> "%.1f KB".format(Locale.US, bytes / 1_024.0)
  else -> "%.1f MB".format(Locale.US, bytes / (1_024.0 * 1_024.0))
}

@Composable
private fun SectionLabel(text: String) {
  Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun EmptyStateCard(text: String, modifier: Modifier = Modifier) {
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
// Screen previews (screenshot tests). AgentDetailScreen itself gets AgentDetailViewModel via
// metroViewModel() on the real route — here is the same visual frame with a fixed state, no ViewModel,
// the same pattern as AgentsPreviewScaffold (AgentsScreen.kt) / HomePreviewScaffold.
// ==================================================================================================

private const val PREVIEW_FIXED_TIMESTAMP = 1_700_000_000_000L

/** A plausible but entirely fake state for previews/screenshot tests — no real ViewModel involved. */
internal fun samplePreviewState(): AgentDetailState {
  val agent = MiniAgentCatalog.get("budget")!!.toDefinition()
  return AgentDetailState(
    isLoading = false,
    definition = agent,
    tools = listOf(
      AgentDetailToolRow(
        name = "memory_tool",
        description = "Сохраняет и читает факты долговременной памяти агента.",
        riskClass = RiskClass.WRITE,
        available = true,
      ),
      AgentDetailToolRow(
        name = "email",
        description = "Готовит черновик письма в почтовом приложении.",
        riskClass = RiskClass.EXTERNAL,
        available = true,
      ),
    ),
    skills = listOf(
      AgentDetailSkillRow(
        id = "budget",
        title = "Бюджет",
        description = "Подробная методика учёта расходов по категориям.",
        whenToUse = "когда нужен разбор трат или предупреждение о перерасходе",
        available = true,
      ),
    ),
    hooks = listOf(
      AgentDetailHookRow(
        hook = AgentHook(
          id = "h1", agentId = "budget", event = HookEvent.BEFORE_TURN,
          conditionType = "always", conditionJson = "{}",
          actionType = HookActionType.ADD_INSTRUCTION, actionJson = "{}",
          priority = 0, enabled = true,
        ),
        recentRuns = emptyList(),
      ),
      AgentDetailHookRow(
        hook = AgentHook(
          id = "h2", agentId = "budget", event = HookEvent.AFTER_TURN,
          conditionType = "always", conditionJson = "{}",
          actionType = HookActionType.LOG, actionJson = "{}",
          priority = 0, enabled = false,
        ),
        recentRuns = emptyList(),
      ),
    ),
    activeHeartbeats = listOf(
      HeartbeatEntity(
        id = "hb1",
        title = "Проверка бюджета",
        mode = "agent",
        prompt = "проверь бюджет и предупреди о перерасходе",
        agentId = "budget",
        conversationId = "conv1",
        kind = "periodic",
        intervalMinutes = 60,
        status = "scheduled",
        lastRunAt = PREVIEW_FIXED_TIMESTAMP,
      ),
    ),
    files = listOf(
      AgentFile(name = "budget_report.txt", sizeBytes = 2_048, lastModified = PREVIEW_FIXED_TIMESTAMP),
    ),
    plans = listOf(
      AgentDetailPlanRow(
        planId = "plan-active-1",
        objective = "Составить бюджет на месяц и отслеживать траты по категориям",
        status = PlanStatus.ACTIVE,
        stepsCompleted = 2,
        stepsTotal = 5,
        updatedAt = PREVIEW_FIXED_TIMESTAMP,
      ),
      AgentDetailPlanRow(
        planId = "plan-completed-1",
        objective = "Проверить подписки и отменить лишние",
        status = PlanStatus.COMPLETED,
        stepsCompleted = 3,
        stepsTotal = 3,
        updatedAt = PREVIEW_FIXED_TIMESTAMP,
      ),
    ),
    hasInvalidCapabilities = false,
  )
}

@Composable
internal fun AgentDetailPreviewScaffold(state: AgentDetailState = samplePreviewState()) {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    AgentDetailScreen(
      state = state,
      onOpenChat = {},
      onDuplicate = {},
      onEdit = {},
      onToggleEnabled = {},
      onArchive = {},
      onNavigateBack = {},
    )
  }
}

/**
 * A focused preview of ONE section on the REAL component (not a mock) — the screen's sections lie below
 * the fold, and a full-screen screenshot does not catch them. The same preview opens in Android Studio to
 * poke at the section on its own. As the remaining sections get restyled, similar focused previews are
 * added.
 */
@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A, heightDp = 240)
@Composable
internal fun ToolsSectionPreview() {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp),
    ) {
      ToolsSection(tools = samplePreviewState().tools)
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A, heightDp = 320)
@Composable
internal fun PromptSectionPreview() {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp),
    ) {
      PromptSection(agent = requireNotNull(samplePreviewState().definition))
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A, heightDp = 220)
@Composable
internal fun HeartbeatSectionPreview() {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp),
    ) {
      HeartbeatSection(heartbeats = samplePreviewState().activeHeartbeats)
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A, heightDp = 240)
@Composable
internal fun StatTilesPreview() {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp),
    ) {
      StatTiles(state = samplePreviewState())
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0A, heightDp = 160)
@Composable
internal fun HooksSectionPreview() {
  MyApplicationTheme(mode = ThemeMode.DARK) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.background)
        .padding(16.dp),
    ) {
      HooksSection(hooks = samplePreviewState().hooks)
    }
  }
}
