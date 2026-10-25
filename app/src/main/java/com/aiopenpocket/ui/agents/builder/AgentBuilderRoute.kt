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
  onExit: () -> Unit,
  /**
   * Переход в чат по id диалога (как `onOpenChat` у `AgentDetailRoute`). null — после публикации
   * только подтверждение, без кнопки «Открыть чат» (так рендерят превью и тесты экрана).
   */
  onOpenChat: ((String) -> Unit)? = null,
) {
  val viewModel: AgentBuilderViewModel = metroViewModel()
  val testViewModel: AgentBuilderTestViewModel = metroViewModel()
  // Ради той же логики «найти чат агента или создать с приветствием», что и у «Открыть чат» на
  // экране агента, — не дублировать её здесь.
  val agentsViewModel: AgentsViewModel = metroViewModel()

  val state by viewModel.state.collectAsState()
  val availableSkillIds by viewModel.availableSkillIds.collectAsState()
  val testStatus by testViewModel.testStatus.collectAsState()
  // Не голый стрим: у хода «План»/«Цель» его нет, ответ лежит в истории (см. testReplyText).
  val testReplyText by testViewModel.testReplyText.collectAsState()
  val testToolTrace by testViewModel.testToolTrace.collectAsState()
  val testPendingApproval by testViewModel.testPendingApproval.collectAsState()

  // Null agentId -> new draft, otherwise load the existing one. LaunchedEffect перезапускается и при
  // повороте экрана — повтор отсекает сам start (см. AgentBuilderViewModel.startedWith).
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
  )
}
