package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.hook.AgentHookRunEntity
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.ui.agents.builder.components.riskClassLabel
import com.aiopenpocket.ui.agents.builder.components.riskColor
import com.aiopenpocket.ui.agents.iconFor
import com.aiopenpocket.ui.components.AcademyCollapsingScaffold
import com.aiopenpocket.ui.components.glass.GlassCard
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.plan.PlanStatusBadge
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.PurpleAccent
import com.aiopenpocket.ui.theme.PurpleAccentDark
import com.aiopenpocket.ui.util.formatRelativeDate
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Full-screen agent detail (Task 21) — a normal `Column` + `verticalScroll` on
 * [AcademyCollapsingScaffold]'s own aurora backdrop (same glass full-screen pattern as
 * `RoadmapScreen`/other tabs), replacing the old glass bottom-sheet inspector. One continuous
 * scroll, no nested tabs/sheets: Header → Actions → clickable stat tiles → full препромпт →
 * Планы → Инструменты → Навыки → Heartbeat → Хуки → Файлы.
 *
 * The Планы section (Task 12, structured-plan-engine-907066) shows active/paused/completed
 * counts plus the full list ([AgentDetailState.plans], sourced from
 * `com.aiopenpocket.agent.core.plan.persistence.PlanRepository.observeByAgent`); tapping a row opens
 * `plan/{planId}` ([onOpenPlan]), and "Все планы" opens the full `plans/{agentId}` roster
 * ([onOpenPlanList], zero-arg like [onOpenChat] — the route wrapper already closes over this
 * agent's own id). A PRESET agent that has never had a plan proposed for it gets an honest empty
 * card, same as the Инструменты/Навыки/Хуки sections' own empty states.
 */
@Composable
fun AgentDetailScreen(
  state: AgentDetailState,
  onOpenChat: () -> Unit,
  onDuplicate: () -> Unit,
  onEdit: () -> Unit,
  onToggleEnabled: () -> Unit,
  onArchive: () -> Unit,
  onNavigateBack: () -> Unit,
  onOpenPlan: (String) -> Unit = {},
  onOpenPlanList: () -> Unit = {},
) {
  val glassState = rememberGlassState()
  AcademyCollapsingScaffold(
    title = state.definition?.name ?: "Агент",
    glassState = glassState,
    navigationIcon = {
      IconButton(onClick = onNavigateBack) {
        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
      }
    },
  ) { padding ->
    val agent = state.definition
    if (agent == null) {
      Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center,
      ) {
        if (state.isLoading) {
          CircularProgressIndicator(color = PurpleAccent)
        } else {
          Text(
            text = "Агент не найден",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
      }
      return@AcademyCollapsingScaffold
    }

    // Клик по стат-плитке скроллит к секции: контейнер меряет свою (стабильную, не зависящую от
    // скролла) позицию в root, каждая секция — свою (которая двигается вместе со скроллом);
    // разница между ними, плюс текущий scrollState.value, даёт content-относительный offset,
    // который и ждёт ScrollState.animateScrollTo.
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var containerTop by remember { mutableStateOf(0) }
    var toolsTop by remember { mutableStateOf(0) }
    var skillsTop by remember { mutableStateOf(0) }
    var heartbeatTop by remember { mutableStateOf(0) }
    var hooksTop by remember { mutableStateOf(0) }

    fun scrollToSection(sectionTop: Int) {
      scope.launch {
        val target = (scrollState.value + (sectionTop - containerTop)).coerceAtLeast(0)
        scrollState.animateScrollTo(target)
      }
    }

    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding)
        .onGloballyPositioned { containerTop = it.positionInRoot().y.toInt() }
        .verticalScroll(scrollState)
        .padding(horizontal = 20.dp)
        .padding(bottom = 40.dp)
        .testTag("agent_detail_scroll"),
    ) {
      Spacer(modifier = Modifier.height(12.dp))
      HeaderSection(agent = agent, onOpenChat = onOpenChat)
      Spacer(modifier = Modifier.height(20.dp))
      ActionsRow(
        state = state,
        onDuplicate = onDuplicate,
        onEdit = onEdit,
        onToggleEnabled = onToggleEnabled,
        onArchive = onArchive,
      )
      if (state.hasInvalidCapabilities) {
        Spacer(modifier = Modifier.height(16.dp))
        InvalidCapabilitiesBanner()
      }
      Spacer(modifier = Modifier.height(20.dp))
      StatTiles(
        state = state,
        onToolsClick = { scrollToSection(toolsTop) },
        onSkillsClick = { scrollToSection(skillsTop) },
        onHeartbeatClick = { scrollToSection(heartbeatTop) },
        onHooksClick = { scrollToSection(hooksTop) },
      )
      Spacer(modifier = Modifier.height(24.dp))
      PromptSection(agent = agent)
      Spacer(modifier = Modifier.height(24.dp))
      PlansSection(state = state, onOpenPlan = onOpenPlan, onOpenPlanList = onOpenPlanList)
      Spacer(modifier = Modifier.height(24.dp))

      Column(modifier = Modifier.onGloballyPositioned { toolsTop = it.positionInRoot().y.toInt() }) {
        ToolsSection(tools = state.tools)
      }
      Spacer(modifier = Modifier.height(24.dp))
      Column(modifier = Modifier.onGloballyPositioned { skillsTop = it.positionInRoot().y.toInt() }) {
        SkillsSection(skills = state.skills)
      }
      Spacer(modifier = Modifier.height(24.dp))
      Column(modifier = Modifier.onGloballyPositioned { heartbeatTop = it.positionInRoot().y.toInt() }) {
        HeartbeatSection(heartbeats = state.activeHeartbeats)
      }
      Spacer(modifier = Modifier.height(24.dp))
      Column(modifier = Modifier.onGloballyPositioned { hooksTop = it.positionInRoot().y.toInt() }) {
        HooksSection(hooks = state.hooks)
      }
      Spacer(modifier = Modifier.height(24.dp))
      FilesSection(agentId = agent.id, files = state.files)
    }
  }
}

@Composable
private fun HeaderSection(agent: AgentDefinition, onOpenChat: () -> Unit, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Box(
        modifier = Modifier
          .size(56.dp)
          .clip(CircleShape)
          .background(PurpleAccent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = iconFor(agent.iconKey),
          contentDescription = null,
          tint = PurpleAccent,
          modifier = Modifier.size(28.dp),
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
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = "${statusLabel(agent.status)} · v${agent.configVersion}",
          style = MaterialTheme.typography.labelMedium,
          color = PurpleAccent,
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

private fun statusLabel(status: AgentStatus): String = when (status) {
  AgentStatus.DRAFT -> "Черновик"
  AgentStatus.PUBLISHED -> "Активен"
  AgentStatus.ARCHIVED -> "Архивирован"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsRow(
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
      ActionChip(label = "Дублировать", onClick = onDuplicate, modifier = Modifier.testTag("agent_detail_action_duplicate"))
    }
    if (state.canEdit) {
      ActionChip(label = "Изменить", onClick = onEdit, modifier = Modifier.testTag("agent_detail_action_edit"))
    }
    if (state.canToggleEnabled) {
      ActionChip(
        label = if (state.isEnabled) "Отключить" else "Включить",
        onClick = onToggleEnabled,
        modifier = Modifier.testTag("agent_detail_action_toggle_enabled"),
      )
    }
    if (state.canArchive) {
      ActionChip(label = "Архивировать", onClick = onArchive, modifier = Modifier.testTag("agent_detail_action_archive"))
    }
  }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
  GlassCard(modifier = modifier, shape = RoundedCornerShape(12.dp), onClick = onClick) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color = PurpleAccent,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
  }
}

@Composable
private fun InvalidCapabilitiesBanner(modifier: Modifier = Modifier) {
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
        text = "Некоторые возможности агента недоступны в этой версии приложения",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
      )
    }
  }
}

@Composable
private fun StatTiles(
  state: AgentDetailState,
  onToolsClick: () -> Unit,
  onSkillsClick: () -> Unit,
  onHeartbeatClick: () -> Unit,
  onHooksClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(label = "Инструменты", value = state.tools.size.toString(), modifier = Modifier.weight(1f), onClick = onToolsClick)
      StatTile(label = "Навыки", value = state.skills.size.toString(), modifier = Modifier.weight(1f), onClick = onSkillsClick)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      StatTile(label = "Heartbeat", value = state.activeHeartbeats.size.toString(), modifier = Modifier.weight(1f), onClick = onHeartbeatClick)
      StatTile(label = "Хуки", value = state.hooks.size.toString(), modifier = Modifier.weight(1f), onClick = onHooksClick)
    }
  }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
  GlassCard(modifier = modifier, shape = RoundedCornerShape(16.dp), onClick = onClick) {
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

/** Lines beyond which the препромпт defaults to a collapsible «Развернуть/Свернуть» toggle (default expanded). */
private const val PROMPT_COLLAPSE_LINES = 40

@Composable
private fun PromptSection(agent: AgentDefinition, modifier: Modifier = Modifier) {
  val lines = remember(agent.systemPrompt) { agent.systemPrompt.lines() }
  val isLong = lines.size > PROMPT_COLLAPSE_LINES
  var expanded by rememberSaveable(agent.id) { mutableStateOf(true) }

  Column(modifier = modifier.fillMaxWidth().testTag("agent_detail_prompt")) {
    SectionLabel("Препромпт")
    Spacer(modifier = Modifier.height(8.dp))
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
      // Полный текст ПРЯМО в потоке скролла экрана — никакого heightIn/вложенного verticalScroll
      // здесь, в отличие от старой шторки (см. бриф Task 21): скроллит только внешний экран.
      val displayText = if (isLong && !expanded) {
        lines.take(PROMPT_COLLAPSE_LINES).joinToString("\n") + "\n…"
      } else {
        agent.systemPrompt
      }
      Text(
        text = displayText,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        modifier = Modifier
          .fillMaxWidth()
          .padding(14.dp),
      )
    }
    if (isLong) {
      Spacer(modifier = Modifier.height(8.dp))
      TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier.testTag("agent_detail_prompt_toggle"),
      ) {
        Text(text = if (expanded) "Свернуть" else "Развернуть")
      }
    }
  }
}

@Composable
private fun PlansSection(
  state: AgentDetailState,
  onOpenPlan: (String) -> Unit,
  onOpenPlanList: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier = modifier.fillMaxWidth().testTag("agent_detail_plans")) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      SectionLabel("Планы")
      Spacer(modifier = Modifier.weight(1f))
      if (state.plans.isNotEmpty()) {
        TextButton(onClick = onOpenPlanList, modifier = Modifier.testTag("agent_detail_plans_see_all")) {
          Text(text = "Все планы", style = MaterialTheme.typography.labelMedium)
        }
      }
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (state.plans.isEmpty()) {
      // Честная пустая карточка — у пресетов и у ещё не запустивших план USER-агентов планов просто нет.
      EmptyStateCard("у агента нет планов")
    } else {
      // Обычный Row (как StatTiles выше), не FlowRow: три ФИКСИРОВАННЫЕ плитки, перенос не нужен —
      // RowScope.weight здесь однозначно доступен, в отличие от FlowRowScope.
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlanCountTile(label = "Активные", count = state.activePlansCount, modifier = Modifier.weight(1f).testTag("agent_detail_plans_active_count"))
        PlanCountTile(label = "На паузе", count = state.pausedPlansCount, modifier = Modifier.weight(1f).testTag("agent_detail_plans_paused_count"))
        PlanCountTile(label = "Завершённые", count = state.completedPlansCount, modifier = Modifier.weight(1f).testTag("agent_detail_plans_completed_count"))
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
      Text(text = count.toString(), style = MaterialTheme.typography.headlineSmall, color = PurpleAccent)
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
          text = "${row.stepsCompleted}/${row.stepsTotal} шагов",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
      PlanStatusBadge(row.status)
    }
  }
}

@Composable
private fun ToolsSection(tools: List<AgentDetailToolRow>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Инструменты")
    Spacer(modifier = Modifier.height(8.dp))
    if (tools.isEmpty()) {
      EmptyStateCard("у агента нет инструментов")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.forEach { row -> ToolRow(row) }
      }
    }
  }
}

@Composable
private fun ToolRow(row: AgentDetailToolRow, modifier: Modifier = Modifier) {
  GlassCard(
    modifier = modifier.fillMaxWidth().testTag("agent_detail_tool_${row.name}"),
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
          text = row.name,
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = row.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
      }
      val risk = row.riskClass
      if (risk != null) RiskBadge(risk) else UnavailableBadge()
    }
  }
}

@Composable
private fun RiskBadge(risk: RiskClass, modifier: Modifier = Modifier) {
  val color = riskColor(risk)
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text = riskClassLabel(risk), style = MaterialTheme.typography.labelMedium, color = color)
  }
}

@Composable
private fun UnavailableBadge(modifier: Modifier = Modifier) {
  val color = MaterialTheme.colorScheme.error
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(8.dp))
      .background(color.copy(alpha = 0.15f))
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(text = "недоступен", style = MaterialTheme.typography.labelMedium, color = color)
  }
}

@Composable
private fun SkillsSection(skills: List<AgentDetailSkillRow>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Навыки")
    Spacer(modifier = Modifier.height(8.dp))
    if (skills.isEmpty()) {
      EmptyStateCard("у агента нет навыков")
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
          text = row.id,
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = row.description,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        if (row.whenToUse.isNotBlank()) {
          Text(
            text = "Когда: ${row.whenToUse}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
          )
        }
      }
      if (!row.available) UnavailableBadge()
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

/** «каждые N мин» для периодических, «разово» для one_time — факт периодичности, без обратного отсчёта. */
private fun formatInterval(hb: HeartbeatEntity): String =
  hb.intervalMinutes?.let { "каждые $it мин" } ?: "разово"

private fun formatHeartbeatStatus(hb: HeartbeatEntity): String = when (hb.status) {
  "fired" -> "Сработало"
  "cancelled" -> "Отменено"
  "error" -> "Ошибка"
  else -> "Ждёт"
}

@Composable
private fun HooksSection(hooks: List<AgentDetailHookRow>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Хуки")
    Spacer(modifier = Modifier.height(8.dp))
    if (hooks.isEmpty()) {
      // Честная пустая карточка — у пресетов и у ещё не настроенных USER-агентов хуков просто нет.
      // Прежние фейковые стабы on_tool_call/pre_message ("на будущее") здесь больше не показываются.
      EmptyStateCard("у агента нет настроенных хуков")
    } else {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        hooks.forEach { row -> HookRow(row) }
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
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = row.hook.id,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "${row.hook.event} → ${row.hook.actionType}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
          )
        }
        HookEnabledBadge(row.hook.enabled)
      }
      if (row.recentRuns.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text = "Последние срабатывания",
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
  val text = buildString {
    append(formatRelativeDate(run.timestamp))
    append(" — ")
    append(run.status)
    run.errorMessage?.let { append(": $it") }
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
  val label = if (enabled) "включён" else "выключен"
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
private fun FilesSection(agentId: String, files: List<AgentFile>, modifier: Modifier = Modifier) {
  Column(modifier = modifier.fillMaxWidth()) {
    SectionLabel("Файлы")
    Spacer(modifier = Modifier.height(8.dp))
    Text(
      text = "Песочница агента (filesDir/agents/$agentId)",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
    Spacer(modifier = Modifier.height(8.dp))
    if (files.isEmpty()) {
      EmptyStateCard("файлов нет")
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

@Composable
private fun SectionLabel(text: String) {
  Text(text = text, style = MaterialTheme.typography.labelLarge, color = PurpleAccent)
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
// Превью экрана (скриншот-тесты). AgentDetailScreen сам тянет AgentDetailViewModel через
// metroViewModel() у настоящего маршрута — здесь тот же визуальный каркас с фиксированным state,
// без ViewModel, паттерн как у AgentsPreviewScaffold (AgentsScreen.kt) / HomePreviewScaffold.
// ==================================================================================================

private const val PREVIEW_FIXED_TIMESTAMP = 1_700_000_000_000L

/** Правдоподобный, но полностью фейковый state для превью/скриншот-тестов — реальный ViewModel не участвует. */
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
        name = "sms",
        description = "Читает последние входящие SMS пользователя.",
        riskClass = RiskClass.READ,
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
        description = "Подробная методика учёта расходов по категориям.",
        whenToUse = "когда нужен разбор трат или предупреждение о перерасходе",
        available = true,
      ),
    ),
    hooks = emptyList(),
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
  MyApplicationTheme {
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
