package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Small sibling to [AgentBuilderViewModel] — kept separate rather than folded in, mirroring how
 * `AgentsScreen` already pairs `AgentsViewModel` with `AgentInspectorViewModel` — that owns the TEST
 * step's own runtime concern: a dedicated test conversation for the draft being edited, and running
 * turns against it through the SAME [TurnManager] a real chat uses. [AgentBuilderScreen]/its step
 * composables never talk to [ConversationRepository]/[TurnManager] directly; only this ViewModel and
 * [AgentBuilderRoute] do.
 *
 * The test conversation is created ONCE per [attachAgent] call and only held in memory here (not
 * looked up via [ConversationRepository.findLatestConversationByAgent]): that lookup would risk
 * reusing a REAL chat the user already has with an already-published agent being edited, which is
 * exactly what "a dedicated test conversation" must not be. A fresh builder session (new
 * [AgentBuilderRoute] composition → new ViewModel instance via `metroViewModel()`) gets a fresh test
 * conversation; within one session, repeated [attachAgent] calls for the SAME agent id are idempotent.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(AgentBuilderTestViewModel::class)
class AgentBuilderTestViewModel(
  private val conversationRepository: ConversationRepository,
  private val turnManager: TurnManager,
) : ViewModel() {

  private val _testConversationId = MutableStateFlow<String?>(null)
  val testConversationId: StateFlow<String?> = _testConversationId.asStateFlow()

  /**
   * Status/streaming text of the test conversation's current (or last) turn, each its OWN flattened
   * top-level [StateFlow] — rather than exposing a single `StateFlow<TurnSession?>` and expecting
   * [AgentBuilderRoute] to collect ITS nested `status`/`streamingText` StateFlows only when a session
   * happens to be non-null, which would mean calling the `@Composable` `collectAsState()` function
   * conditionally (disallowed). Both are flattened here, in plain Kotlin Flow composition, so the
   * route can call `collectAsState()` on each unconditionally.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  val testStatus: StateFlow<TurnStatus?> = _testConversationId
    .flatMapLatest { id -> if (id == null) flowOf(null) else turnManager.session(id) }
    .flatMapLatest { session -> session?.status ?: flowOf(null) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  @OptIn(ExperimentalCoroutinesApi::class)
  val testStreamingText: StateFlow<String> = _testConversationId
    .flatMapLatest { id -> if (id == null) flowOf(null) else turnManager.session(id) }
    .flatMapLatest { session -> session?.streamingText ?: flowOf("") }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

  private var attachedAgentId: String? = null

  /**
   * Binds the TEST step to [agentId]'s dedicated test conversation, creating it the first time this
   * is called for this agent id (call from e.g. `LaunchedEffect(draft.id)` once the draft has
   * loaded — repeat calls for the SAME [agentId] are a no-op).
   */
  fun attachAgent(agentId: String) {
    if (attachedAgentId == agentId) return
    attachedAgentId = agentId
    viewModelScope.launch {
      _testConversationId.value = conversationRepository.createConversation(
        title = TEST_CONVERSATION_TITLE,
        agentId = agentId,
      )
    }
  }

  /** Sends [text] into the bound test conversation under [executionMode] (PREVIEW by default — see [AgentBuilderRoute]). */
  fun sendTestMessage(text: String, mode: AgentMode, executionMode: ExecutionMode) {
    val convId = _testConversationId.value ?: return
    if (text.isBlank()) return
    turnManager.launchTurn(
      conversationId = convId,
      userText = text,
      mode = mode,
      planApproved = false,
      goalMaxIterations = DEFAULT_GOAL_MAX_ITERATIONS,
      executionMode = executionMode,
    )
  }

  companion object {
    private const val TEST_CONVERSATION_TITLE = "Тест агента"
    private const val DEFAULT_GOAL_MAX_ITERATIONS = 3
  }
}
