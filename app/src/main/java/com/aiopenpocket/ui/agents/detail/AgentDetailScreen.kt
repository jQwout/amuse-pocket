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
import com.aiopenpocket.ui.theme.ThemeMode
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
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
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

