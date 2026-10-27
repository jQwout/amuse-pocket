package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
//
// Screen previews (screenshot tests). AgentDetailScreen itself gets AgentDetailViewModel via
// metroViewModel() on the real route — here is the same visual frame with a fixed state, no ViewModel,
// the same pattern as AgentsPreviewScaffold (AgentsScreen.kt) / HomePreviewScaffold.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.HookActionType
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.plan.model.PlanStatus
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode

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
