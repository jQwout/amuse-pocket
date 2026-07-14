package com.aiopenpocket.ui.agents.detail

// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aiopenpocket.ui.agents.AgentsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Route wrapper for `agent/{agentId}` (Task 21): obtains [AgentDetailViewModel] (this screen's own
 * aggregated state) and, purely to reuse the exact same find-latest-or-create + greeting logic
 * "Открыть чат" needs, [AgentsViewModel] — the same "grab more than one `metroViewModel()` per
 * route" pattern `AgentBuilderRoute` already uses for its own two ViewModels. Hosts every
 * navigation/lifecycle callback the screen's actions need:
 * - `onOpenChat`: opens (or creates, and reuses across repeat visits) this agent's conversation.
 * - `onDuplicate`: forks the agent into a fresh USER draft ([AgentDetailViewModel.duplicate]), then
 *   opens the builder ALREADY on that draft (`agent_builder/{clonedId}`) — the "prefilled-clone" the
 *   Task 21 brief asks for, reusing the existing (unmodified) edit route rather than a new one.
 * - `onEdit`: opens the builder directly on this (necessarily [com.aiopenpocket.agent.core.agent.AgentSource.USER]) agent's own id.
 * - `onArchive`/`onToggleEnabled`: no navigation at all — [AgentDetailViewModel] mutates in place and
 *   reloads its own state; the screen just re-renders.
 * - `onOpenPlan`/`onOpenPlanList` (Task 12): open `plan/{planId}`/`plans/{agentId}` — the latter
 *   already closes over THIS route's own `agentId`, matching `onOpenChat`'s own shape.
 */
@Composable
fun AgentDetailRoute(
  agentId: String,
  onOpenChat: (String) -> Unit,
  onNavigateToBuilder: (String) -> Unit,
  onNavigateBack: () -> Unit,
  onOpenPlan: (String) -> Unit = {},
  onOpenPlanList: (String) -> Unit = {},
) {
  val viewModel: AgentDetailViewModel = metroViewModel()
  val agentsViewModel: AgentsViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  // Once per navigation entry (mirrors AgentBuilderRoute's LaunchedEffect(agentId) { viewModel.start(agentId) }).
  LaunchedEffect(agentId) { viewModel.select(agentId) }

  AgentDetailScreen(
    state = state,
    onOpenChat = { agentsViewModel.openAgentChat(agentId, onOpenChat) },
    onDuplicate = { viewModel.duplicate(onReady = onNavigateToBuilder) },
    onEdit = { onNavigateToBuilder(agentId) },
    onToggleEnabled = viewModel::toggleEnabled,
    onArchive = viewModel::archive,
    onNavigateBack = onNavigateBack,
    onOpenPlan = onOpenPlan,
    onOpenPlanList = { onOpenPlanList(agentId) },
  )
}
