package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.memory.repository.ConversationHeartbeatCleanup
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Small sibling to [AgentBuilderViewModel] — kept separate rather than folded in, mirroring how
 * `com.aiopenpocket.ui.agents.detail.AgentDetailRoute` already pairs `AgentDetailViewModel` with
 * `AgentsViewModel` — that owns the TEST step's own runtime concern: a dedicated test conversation
 * for the draft being edited, and running turns against it through the SAME [TurnManager] a real
 * chat uses. [AgentBuilderScreen]/its step
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
@ViewModelKey
class AgentBuilderTestViewModel(
  private val conversationRepository: ConversationRepository,
  private val turnManager: TurnManager,
  private val toolApprovalCoordinator: ToolApprovalCoordinator,
  private val conversationHeartbeatCleanup: ConversationHeartbeatCleanup,
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

  /**
   * Tool calls the test run actually made, flattened the same way as [testStatus]/[testStreamingText].
   * The TEST step used to show only the reply text, which made a PREVIEW run indistinguishable from
   * a LIVE one from the user's side — the whole point of preview is seeing WHICH tools the agent
   * reached for and what came back (`PolicyDecision.Simulate` results included).
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  val testToolTrace: StateFlow<List<ToolTrace>> = _testConversationId
    .flatMapLatest { id -> if (id == null) flowOf(null) else turnManager.session(id) }
    .flatMapLatest { session -> session?.toolTrace ?: flowOf(emptyList()) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

  /**
   * Последнее сообщение тестового диалога, если оно от ассистента. Нужно ходам «План»/«Цель»: их
   * итог (`TurnManager.finishPlanMessage`) пишется сразу в историю сообщением, без стриминга, и
   * [testStreamingText] у такого хода пуст — шаг «Проверка» показывал «Пустой ответ.» агенту,
   * который на деле ответил. Роль проверяем, чтобы при пустом ответе обычного хода (последней
   * будет реплика пользователя) не всплыл ответ прошлого хода.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  private val lastAssistantMessage: Flow<String> = _testConversationId
    .flatMapLatest { id ->
      if (id == null) {
        flowOf("")
      } else {
        conversationRepository.getMessagesWindow(id, limit = 1)
          .map { window -> window.lastOrNull()?.takeIf { it.role == "assistant" }?.content.orEmpty() }
      }
    }

  /**
   * То, что шаг «Проверка» показывает как ответ агента: сообщение, которое ход записал в тестовый
   * диалог (см. [lastAssistantMessage]), а пока его нет — стрим хода. Записанное — первым: у хода
   * «План»/«Цель» стрим пуст или застревает на строке прогресса инструмента, а итоговый текст есть
   * только в истории. Новый ход начинается с реплики пользователя, поэтому ответ прошлого хода сюда
   * не протекает.
   */
  val testReplyText: StateFlow<String> = combine(testStreamingText, lastAssistantMessage) { streaming, saved ->
    saved.ifBlank { streaming }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

  /**
   * Запрос подтверждения от хука «Требовать подтверждение» в ТЕСТОВОМ диалоге — null, пока ничего не
   * ждёт. [ExecutionPolicy][com.aiopenpocket.agent.core.policy.ExecutionPolicy] ставит такой вызов на
   * ожидание и в предпросмотре, а ответить на него раньше было негде: бар подтверждения жил только в
   * чате, и тестовый ход молча висел две минуты до таймаута координатора. Фильтр по своему
   * conversationId — как в `ChatViewModel`: чужой запрос сюда не попадёт.
   */
  val testPendingApproval: StateFlow<ToolApprovalRequest?> =
    combine(_testConversationId, toolApprovalCoordinator.pending) { convId, pending ->
      if (convId == null) null else pending.firstOrNull { it.conversationId == convId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

  /** Ответ на [testPendingApproval]; no-op, если запрос уже снят (таймаут, ход закончился). */
  fun resolveTestApproval(approved: Boolean) {
    val request = testPendingApproval.value ?: return
    toolApprovalCoordinator.resolve(request.runId, request.requestId, approved)
  }

  private var attachedAgentId: String? = null

  /**
   * Binds the TEST step to [agentId]'s dedicated test conversation, creating it the first time this
   * is called for this agent id (call from e.g. `LaunchedEffect(draft.id)` once the draft has
   * loaded — repeat calls for the SAME [agentId] are a no-op).
   */
  fun attachAgent(agentId: String) {
    if (attachedAgentId == agentId) return
    attachedAgentId = agentId
    // Смена агента в той же сессии: прежний тестовый диалог больше не нужен никому. Раньше он
    // оставался висеть — [onCleared] убирал только последний, и каждый лишний id (например, новый
    // черновик после поворота экрана) оставлял в списке чатов ещё один «Тест агента».
    _testConversationId.value?.let(::discardConversation)
    _testConversationId.value = null
    viewModelScope.launch {
      val created = conversationRepository.createConversation(
        title = TEST_CONVERSATION_TITLE,
        agentId = agentId,
        // Помечаем служебным тегом: диалог привязан к agentId черновика, и без метки «Открыть чат»
        // у собранного агента мог переиспользовать именно его (findLatestConversationByAgent).
        tags = listOf(ConversationRepository.BUILDER_TEST_TAG),
      )
      // Пока диалог создавался, могли успеть переключиться на другого агента — тогда этот уже сирота.
      if (attachedAgentId == agentId) _testConversationId.value = created else discardConversation(created)
    }
  }

  /** Sends [text] into the bound test conversation under [executionMode] (PREVIEW by default — see [AgentBuilderRoute]). */
  fun sendTestMessage(text: String, mode: AgentMode, executionMode: ExecutionMode) {
    val convId = _testConversationId.value ?: return
    if (text.isBlank()) return
    // Task 10 (structured-plan-engine-907066): planId/budget default (null / PlanRunBudget()) — the
    // builder's TEST step has no plan-id concept of its own yet; StructuredPlanOrchestrator resolves
    // whatever plan (if any) the test conversation already has, same as an ordinary chat GOAL turn.
    turnManager.launchTurn(
      conversationId = convId,
      userText = text,
      mode = mode,
      executionMode = executionMode,
    )
  }

  /**
   * The test conversation is scratch space, not something the user asked to keep: it was created
   * silently by opening the wizard's TEST step and then left behind in the chat list forever, one
   * «Тест агента» per builder session. Cleaning it up here (the ViewModel dies with the builder
   * screen) keeps that list to conversations the user actually started.
   *
   * The turn is stopped FIRST and awaited: closing the wizard mid-test-run is entirely normal, and
   * `messages` has a CASCADE foreign key onto `conversations` — deleting the row out from under a
   * live turn makes its answer write fail on a constraint, which surfaces to the user as a mystery
   * error in a chat they can no longer even open. [TurnManager.cancelAndAwait] exists for exactly
   * this ordering requirement; plain `cancel` returns before the turn has actually stopped.
   *
   * Fire-and-forget beyond that is fine: the delete is a single local Room write, and if the process
   * dies mid-way the leftover row is exactly the old behavior, not a corruption.
   *
   * Напоминания тестового диалога снимаются ДО удаления: в LIVE-проверке агент мог поставить
   * heartbeat (или план — свой), а у `heartbeats` нет FK на диалог, каскад их не трогает. Осиротевший
   * heartbeat продолжал бы будить LLM, падать на записи в несуществующий чат и слать пуш со ссылкой
   * в никуда — та же уборка, что при удалении чата из списка ([ConversationHeartbeatCleanup]).
   */
  override fun onCleared() {
    super.onCleared()
    val convId = _testConversationId.value ?: return
    _testConversationId.value = null
    discardConversation(convId)
  }

  /** Останавливает ход в [convId] и удаляет диалог — порядок и причины см. в [onCleared]. */
  private fun discardConversation(convId: String) {
    cleanupScope.launch {
      runCatching {
        turnManager.cancelAndAwait(convId)
        conversationHeartbeatCleanup.cancelForConversation(convId)
        conversationRepository.deleteConversation(convId)
      }
    }
  }

  companion object {
    private const val TEST_CONVERSATION_TITLE = "Тест агента"

    /**
     * Один общий скоуп на все экземпляры вместо нового на каждое [onCleared]: уборка обязана
     * пережить смерть самой ViewModel (её `viewModelScope` к этому моменту уже отменён), но плодить
     * по скоупу на закрытие экрана незачем.
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }
}
