package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aiopenpocket.agent.core.policy.ExecutionMode
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Entry point for both builder routes (`agent_builder` and `agent_builder/{agentId}` in
 * `MainActivity`). Obtains [AgentBuilderViewModel] (Task 19) and [AgentBuilderTestViewModel] via the
 * app's Metro `metroViewModel()` factory — mirroring how `AgentsScreen` obtains both
 * `AgentsViewModel` and `AgentInspectorViewModel` the same way — collects their state, and hosts
 * every navigation/lifecycle callback. [AgentBuilderScreen] itself stays a pure projector: it never
 * imports either ViewModel.
 */
@Composable
fun AgentBuilderRoute(
  agentId: String?,
  onExit: () -> Unit,
) {
  val viewModel: AgentBuilderViewModel = metroViewModel()
  val testViewModel: AgentBuilderTestViewModel = metroViewModel()

  val state by viewModel.state.collectAsState()
  val availableSkillIds by viewModel.availableSkillIds.collectAsState()
  val testStatus by testViewModel.testStatus.collectAsState()
  val testStreamingText by testViewModel.testStreamingText.collectAsState()

  // Null agentId -> new draft, otherwise load the existing one. Call once per navigation entry, not
  // on every recomposition (mirrors OnboardingScreen's `LaunchedEffect(editMode) { ... }`).
  LaunchedEffect(agentId) { viewModel.start(agentId) }

  // Task 20 brief: Preview must be the TEST step's default run mode. AgentBuilderUiState's own field
  // default is ExecutionMode.LIVE (see its KDoc for why) — Task 19 is frozen/out of this task's file
  // scope, so this is the compensating fix, scoped to the route rather than that state class.
  LaunchedEffect(Unit) { viewModel.setExecutionMode(ExecutionMode.PREVIEW) }

  // Binds the TEST step's dedicated test conversation once the real draft id is known (a brand-new
  // draft's id is only generated once `start`'s coroutine finishes — see AgentBuilderViewModel.newDraft).
  LaunchedEffect(state.draft.id, state.isLoading) {
    if (!state.isLoading && state.draft.id.isNotBlank()) {
      testViewModel.attachAgent(state.draft.id)
    }
  }

  AgentBuilderScreen(
    state = state,
    availableSkillIds = availableSkillIds,
    testStatus = testStatus,
    testStreamingText = testStreamingText,
    onUpdateDraft = viewModel::updateDraft,
    onBack = { if (!viewModel.previousStep()) onExit() },
    onNext = { viewModel.nextStep() },
    onExit = onExit,
    onSetExecutionMode = viewModel::setExecutionMode,
    onSendTestMessage = { text ->
      testViewModel.sendTestMessage(text, state.draft.defaultMode, state.executionMode)
    },
    onPublish = viewModel::publish,
    onArchive = viewModel::archive,
  )
}
