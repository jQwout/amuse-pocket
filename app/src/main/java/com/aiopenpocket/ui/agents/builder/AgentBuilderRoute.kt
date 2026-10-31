package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aiopenpocket.ui.agents.AgentsViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * Entry point for both builder routes (`agent_builder` and `agent_builder/{agentId}` in
 * `MainActivity`). Obtains [AgentBuilderViewModel] (Task 19) and [AgentBuilderTestViewModel] via the
 * app's Metro `metroViewModel()` factory — mirroring how `com.aiopenpocket.ui.agents.detail.AgentDetailRoute`
 * obtains both `AgentDetailViewModel` and `AgentsViewModel` the same way — collects their state, and
 * hosts every navigation/lifecycle callback. [AgentBuilderScreen] itself stays a pure projector: it
 * never imports either ViewModel.
 */
@Composable
fun AgentBuilderRoute(
  agentId: String?,
  // AI Academy capstone completion CTA (Task 23): MainActivity's "agent_builder?source={source}"
  // route resolves its nav arg down to this before falling back to MANUAL. Only read when
  // [agentId] is null (new draft) — see [AgentBuilderViewModel.start].
  sourceContext: AgentSourceContext = AgentSourceContext.MANUAL,
  /**
   * Opened from the builder chat's "Review your agent" card (`agent_builder/{agentId}?review=true`): the
   * review screen should show instead of the first step. Not read yet - the review screen is built on its
   * own branch and wires this parameter when it lands.
   */
  openReview: Boolean = false,
  onExit: () -> Unit,
  /**
   * Navigates to the chat by conversation id (like `onOpenChat` in `AgentDetailRoute`). null — after
   * publishing only a confirmation, without the "Open chat" button (that is how previews and screen
   * tests render it).
   */
  onOpenChat: ((String) -> Unit)? = null,
  /** "Open Settings" on the test run's no-key message (the Meta key is entered there). */
  onOpenSettings: (() -> Unit)? = null,
) {
  val viewModel: AgentBuilderViewModel = metroViewModel()
  val testViewModel: AgentBuilderTestViewModel = metroViewModel()
  // For the sake of the same "find the agent's chat or create one with a greeting" logic as "Open chat"
  // on the agent screen — not to duplicate it here.
  val agentsViewModel: AgentsViewModel = metroViewModel()

  val state by viewModel.state.collectAsState()
  val availableSkillIds by viewModel.availableSkillIds.collectAsState()
  val testStatus by testViewModel.testStatus.collectAsState()
  // Not the bare stream: a "Plan"/"Goal" turn has none, the answer is in the history (see testReplyText).
  val testReplyText by testViewModel.testReplyText.collectAsState()
  val testToolTrace by testViewModel.testToolTrace.collectAsState()
  val testPendingApproval by testViewModel.testPendingApproval.collectAsState()

  // Null agentId -> new draft, otherwise load the existing one. The LaunchedEffect restarts on a screen
  // rotation too — start itself cuts off the repeat (see AgentBuilderViewModel.startedWith).
  LaunchedEffect(agentId, sourceContext) { viewModel.start(agentId, sourceContext) }

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
    testStreamingText = testReplyText,
    testToolTrace = testToolTrace,
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
    testPendingApproval = testPendingApproval,
    onResolveTestApproval = testViewModel::resolveTestApproval,
    onOpenChat = onOpenChat?.let { open -> { agentsViewModel.openAgentChat(state.draft.id, open) } },
    onOpenSettings = onOpenSettings,
  )
}
