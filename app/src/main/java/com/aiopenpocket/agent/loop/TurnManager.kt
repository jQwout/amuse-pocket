package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066
// @trace approval-outcome-honesty-554a8c

import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent turns decoupled from the screen lifecycle: an appScope singleton keeps one TurnSession per
 * conversation, and a turn keeps running in the background after the user leaves the chat screen.
 * Within a conversation turns are serialized (a new one cancelAndJoins the previous, like the old
 * launchTurn in the ViewModel); across conversations they run in parallel. The screen is only a
 * projector: it subscribes to session(convId) and unsubscribes, and the turn does not care.
 *
 * The survival boundary is the process: a turn does not survive process death or a swipe from
 * recents (a deliberate trade-off instead of a foreground service, see the spec
 * 2026-07-04-background-turns-design.md).
 */
class TurnManager(
  private val executor: TurnExecutor,
  private val persistence: TurnPersistence,
  private val foreground: ForegroundState,
  private val notifier: TurnNotifier,
  /** "Is this approval still waiting?" — the gate of [watchPendingApproval]'s polling loop. */
  private val pendingApprovals: PendingApprovals,
  private val lifeSupport: TurnLifeSupport,
  private val scope: CoroutineScope,
  /** Monotonic clock for the stream gate (SystemClock.uptimeMillis in production, a fake in tests). */
  private val monotonicNowMs: () -> Long,
  /**
   * The provider in effect for the user's Settings (see activeLlmProvider). A lambda, not a value —
   * like [monotonicNowMs] and `apiKeyResolver`: it is read again on EVERY turn, so a change is
   * picked up from the very next message, without rebuilding the DI graph.
   *
   * No default value ON PURPOSE (like toolApprovalCoordinator in ReactLoopRunner): `= {
   * LlmProvider.NIM }` meant that an argument lost in DI silently sent the whole app back to
   * NVIDIA without anything failing.
   */
  private val selectedProvider: () -> LlmProvider,
  private val log: AgentLog,
) {

  /** Guards the session map and job swaps — launchTurn can arrive from any thread. */
  private val mutex = Mutex()

  private val sessionsFlow = MutableStateFlow<Map<String, TurnSession>>(emptyMap())

  /**
   * Cancelled turns that have not finished yet, per conversation. Turn serialization used to rest on
   * `session.job` alone, while Stop/closing an error removes the whole session: the next turn created
   * a new session without a job and started without waiting for the previous one to stop — which
   * could still be writing an answer or a tool result into the same conversation. Now every
   * cancelled turn stays here until it actually finishes, and a new turn of the conversation waits
   * for all of them (see [startTurn]).
   *
   * Plain `synchronized`, not [mutex]: entries are removed from `invokeOnCompletion`, which cannot
   * suspend.
   */
  private val dyingJobs = HashMap<String, MutableSet<Job>>()

  /**
   * `systemPromptExtra` of the last turn per conversation — for [retry]. A separate map rather than
   * a field of [TurnSession] (where the rest of the retry context lives) only to avoid touching the
   * session file in that pass; every turn overwrites it, so a stale value never reaches a retry.
   */
  private val lastSystemPromptExtra = ConcurrentHashMap<String, String>()

  init {
    // The only subscriber: only the manager owns turn jobs and can cancel them.
    lifeSupport.onProtectionLost { protectedIds -> scope.launch { failProtectedTurns(protectedIds) } }
  }

  /** Active and finished-but-unseen sessions by conversationId (for the list badges). */
  val sessions: StateFlow<Map<String, TurnSession>> = sessionsFlow.asStateFlow()

  fun session(conversationId: String): Flow<TurnSession?> =
    sessionsFlow.map { it[conversationId] }.distinctUntilChanged()

  /**
   * Starts a turn: writes the user's message into history and drives the agent loop to the end.
   * [executionMode] — LIVE (the default, a normal chat) or PREVIEW (the builder test, Task 20) — does
   * not create a parallel entry point: it rides the same path as a normal send.
   *
   * [planId]/[budget] (Task 10, structured-plan-engine-907066) replace the old
   * `planApproved`/`goalMaxIterations`: [AgentMode.GOAL] reads [planId] (null = StructuredPlanOrchestrator
   * resolves the conversation's plan itself) and a multi-dimensional [PlanRunBudget] instead of a single
   * iteration count. Both are ignored by CHAT/TUTOR/REFLECT.
   */
  fun launchTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planId: String? = null,
    budget: PlanRunBudget = PlanRunBudget(),
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    scope.launch {
      startTurn(
        conversationId, userText, mode, planId, budget,
        persistUserMessage = true, systemPromptExtra = systemPromptExtra, executionMode = executionMode,
      )
    }
  }

  /** Stops the turn and removes the session: for the UI the conversation goes back to Idle, no error. */
  fun cancel(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@withLock
        session.job?.let { retire(conversationId, it) }
        sessionsFlow.update { it - conversationId }
      }
    }
  }

  /**
   * Cancels the turn and WAITS for it to actually stop, then forgets the session. Unlike [cancel],
   * which returns at once: that is not enough for a caller that deletes the conversation right after —
   * `messages` has a CASCADE foreign key to `conversations`, and deleting from under a still-running
   * turn makes the answer write fail on the constraint. The only such caller today is the cleanup of
   * the builder's test conversation (`AgentBuilderTestViewModel.onCleared`).
   */
  suspend fun cancelAndAwait(conversationId: String) {
    val job = mutex.withLock {
      val session = sessionsFlow.value[conversationId] ?: return@withLock null
      sessionsFlow.update { it - conversationId }
      session.job?.also { retire(conversationId, it) }
    }
    job?.join()
  }

  /**
   * Retries the last turn after an error: the user's message is already in history, so addMessage is
   * not repeated. It also repeats the previous attempt's [TurnSession.lastExecutionMode] — "Retry"
   * must not replay a PREVIEW turn as LIVE (or the other way round).
   *
   * Task 10 (structured-plan-engine-907066): repeats [TurnSession.lastPlanId]/[TurnSession.lastBudget]
   * as is — no special logic is needed any more (unlike the old hard `planApproved=false`): approval is
   * now persisted state in PlanRepository, not a turn flag, so a plan-mode retry simply calls
   * propose()/executeGoal() again with the same arguments as the original turn.
   */
  fun retry(conversationId: String) {
    scope.launch {
      val session = sessionsFlow.value[conversationId] ?: return@launch
      val text = session.lastUserText ?: return@launch
      startTurn(
        conversationId, text, session.lastMode, session.lastPlanId, session.lastBudget,
        persistUserMessage = false,
        // The turn's context is repeated with it: a question in the middle of a lesson without the
        // current step's text turned into a context-free question on "Retry", and the model answered
        // about the wrong step.
        systemPromptExtra = lastSystemPromptExtra[conversationId],
        executionMode = session.lastExecutionMode,
      )
    }
  }

  /** Changing the agent mode invalidates the previous turn's "Retry" (or the retry would use the old mode). */
  fun invalidateRetry(conversationId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    session.lastUserText = null
    session.lastUserMessageId = null
    session.statusFlow.update { status ->
      when (status) {
        is TurnStatus.Failed -> status.copy(canRetry = false)
        is TurnStatus.Completed -> status.copy(canRetry = false)
        else -> status
      }
    }
  }

  /** The message that holds "Retry" was deleted — cancel the retry (see deleteMessage before the move). */
  fun invalidateRetryFor(conversationId: String, messageId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    if (session.lastUserMessageId == messageId) invalidateRetry(conversationId)
  }

  /** ClearError intent: the user closed the error banner — the failed session is no longer needed. */
  fun clearError(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@withLock
        val status = session.status.value
        val hasError = status is TurnStatus.Failed || (status is TurnStatus.Completed && status.error != null)
        if (hasError) {
          session.job?.let { retire(conversationId, it) }
          sessionsFlow.update { it - conversationId }
        }
      }
    }
  }

  /**
   * The turn was seen (the user opened the conversation, or the answer reached the feed) — a cleanly
   * finished session is removed so that the "done" badge in the list goes out. Running/Failed/
   * blank-error are left alone: the screen still needs them (progress, retry).
   *
   * Before Task 5 (structured-plan-engine-907066) this also checked `!status.planAwaitingApproval` —
   * a plan waiting for approval kept the session visible so the approval bar would not disappear on
   * its own. That flag was removed from TurnStatus.Completed together with the approvePlan magic
   * message; "is the plan waiting for a decision" is now PlanRepository state (see PlanCommands), not
   * this session's, so markSeen no longer special-cases it — a cleanly finished turn (including an
   * error-free plan phase) is removed as usual.
   */
  fun markSeen(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@withLock
        val status = session.status.value
        val cleanlyDone = status is TurnStatus.Completed && status.error == null && !status.canRetry
        if (cleanlyDone) {
          sessionsFlow.update { it - conversationId }
        }
      }
    }
  }

  /** Turn statuses per conversation — the conversation list badges ("cooking" / "done" / "error"). */
  @OptIn(ExperimentalCoroutinesApi::class)
  val badges: Flow<Map<String, TurnStatus>> = sessionsFlow.flatMapLatest { map ->
    if (map.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(map.map { (id, session) -> session.status.map { id to it } }) { pairs -> pairs.toMap() }
    }
  }

  /**
   * The single start point (send/retry). The previous turn of the same conversation may still be
   * running a tool or a write — cancel it and wait inside the new job before starting (the semantics
   * of the old ChatViewModel.launchTurn, but keyed by conversation instead of one global activeJob).
   */
  internal suspend fun startTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planId: String?,
    budget: PlanRunBudget,
    persistUserMessage: Boolean,
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    // Acquired BEFORE the mutex: acquire calls into Android (starts a service), and there is no reason
    // to hold the manager's mutex over it — the mutex only serializes job swaps. A refusal by the
    // system is not an error: the turn simply runs unprotected, exactly as before this feature.
    val hasProtection = lifeSupport.acquire(conversationId)
    mutex.withLock {
      val session = sessionsFlow.value[conversationId] ?: TurnSession(conversationId, monotonicNowMs).also { created ->
        sessionsFlow.update { it + (conversationId to created) }
      }
      // Cancel the previous turn RIGHT AWAY under the mutex, and join it inside the new job. Otherwise,
      // if the new job were cancelled (Stop/clearError) before it reached cancelAndJoin, the previous
      // turn would stay half-cancelled and keep writing its answer into a "stopped" conversation.
      session.job?.let { retire(conversationId, it) }
      // Wait for ALL unfinished turns of the conversation, not only this session's job: Stop may have
      // removed the session (then job is null here), and the previous turn may itself have been
      // cancelled while waiting for the one before it.
      val previous = dyingJobsOf(conversationId)
      session.job = scope.launch {
        try {
          previous.joinAll()
          runTurn(session, userText, mode, planId, budget, persistUserMessage, systemPromptExtra, executionMode)
        } finally {
          // finally, not the tail of runTurn: a cancellation (Stop, a new turn) must release the protection
          // too, or the service with its notification would outlive the turn it was started for.
          if (hasProtection) lifeSupport.release(conversationId)
        }
      }
    }
  }

  /**
   * Cancels [job] and keeps it in the conversation's [dyingJobs] until it actually finishes — so the
   * next turn of this conversation waits for it even if the session is already gone.
   */
  private fun retire(conversationId: String, job: Job) {
    job.cancel()
    synchronized(dyingJobs) { dyingJobs.getOrPut(conversationId) { HashSet() }.add(job) }
    // For a job that has already finished the handler runs immediately — the entry is removed at once.
    job.invokeOnCompletion {
      synchronized(dyingJobs) {
        val jobs = dyingJobs[conversationId] ?: return@synchronized
        jobs.remove(job)
        if (jobs.isEmpty()) dyingJobs.remove(conversationId)
      }
    }
  }

  private fun dyingJobsOf(conversationId: String): List<Job> =
    synchronized(dyingJobs) { dyingJobs[conversationId]?.toList().orEmpty() }

  /**
   * The system took the protection away (shortService timeout). The visibility gate is the key: the
   * OS does not evict a foreground process, so on screen there is nothing to protect against, and
   * cutting a long GOAL run there would do harm for no reason. In the background the alternative —
   * leaving the turn unprotected — means a silently lost answer, so we fail explicitly, with "Retry".
   */
  private suspend fun failProtectedTurns(protectedIds: Set<String>) {
    if (foreground.isInForeground()) return
    mutex.withLock {
      protectedIds.forEach { convId ->
        val session = sessionsFlow.value[convId] ?: return@forEach
        if (session.status.value !is TurnStatus.Running) return@forEach
        // cancelAndJoin, not cancel: the turn does not hold this mutex while it runs, so between "saw
        // Running" and writing Failed it could still write Completed together with its answer to the
        // DB — and we would overwrite a valid result with an error right when the turn had almost
        // made it. Wait for it to actually stop and check the status again.
        session.job?.cancelAndJoin()
        if (session.status.value !is TurnStatus.Running) return@forEach
        session.statusFlow.value =
          TurnStatus.Failed(TurnTexts.PROTECTION_EXPIRED, canRetry = session.lastUserText != null)
      }
    }
  }

  private suspend fun runTurn(
    session: TurnSession,
    userText: String,
    mode: AgentMode,
    planId: String?,
    budget: PlanRunBudget,
    persistUserMessage: Boolean,
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    val convId = session.conversationId
    resetStreaming(session)
    session.toolTraceFlow.value = emptyList()
    session.responseStartTime = System.currentTimeMillis()
    session.statusFlow.value = TurnStatus.Running
    session.lastMode = mode
    session.lastPlanId = planId
    session.lastBudget = budget
    session.lastExecutionMode = executionMode
    if (systemPromptExtra != null) {
      lastSystemPromptExtra[convId] = systemPromptExtra
    } else {
      lastSystemPromptExtra.remove(convId)
    }
    try {
      if (persistUserMessage) {
        // A new turn is a new attempt: the retry context is valid only after a successful addMessage.
        session.lastUserText = null
        session.lastUserMessageId = null
        val userMsgId = persistence.addUserMessage(convId, userText)
        session.lastUserText = userText
        session.lastUserMessageId = userMsgId
      }
      collectTurnEvents(session, userText, mode, planId, budget, systemPromptExtra, executionMode)
    } catch (e: CancellationException) {
      // Stop / a new turn: cancellation is the normal path.
      throw e
    } catch (e: Exception) {
      log.e(TAG, "Agent turn error", e)
      // The exception text (a Room write, a prompt assembly failure) tells a person nothing and is sometimes in English.
      finishFailed(session, TurnTexts.TURN_FAILED)
    }
  }

  /** Projects agent-loop events into the session — a direct port of the branches of ChatViewModel.runAgent. */
  private suspend fun collectTurnEvents(
    session: TurnSession,
    userText: String,
    mode: AgentMode,
    planId: String?,
    budget: PlanRunBudget,
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ) {
    // The provider comes from Settings on every turn. There used to be a hard-wired NIM here marked
    // "remove later" — keeping it would have made the provider choice a control without effect.
    val flow = executor.execute(session.conversationId, userText, selectedProvider(), mode, planId, budget, systemPromptExtra, executionMode)
    flow.collect { event ->
      when (event) {
        is AgentEvent.TextDelta ->
          // Rate gate (~20 publishes/s) lives in StreamingTextBuffer; null = the delta was buffered silently.
          session.streamBuffer.append(event.content)?.let { session.streamingTextFlow.value = it }

        is AgentEvent.ToolCompleted -> {
          val newTrace = ToolTrace(
            iteration = event.iteration,
            toolName = event.name,
            arguments = event.arguments,
            result = event.result,
          )
          session.toolTraceFlow.update { it + newTrace }
        }

        is AgentEvent.Completed -> handleCompleted(session, event.result)

        is AgentEvent.Error -> {
          // GOAL with a failed step: first what the steps managed to say (the background heartbeat writes
          // it on FAILED too), then the error — the banner and "Retry" stay as for any error. The trace
          // goes to the tools panel, as for a successful run. For other errors the list is empty.
          if (event.planStepOutputs.isNotEmpty()) {
            persistPlanStepOutputs(event.planStepOutputs) { text, card ->
              persistence.addAssistantMessage(session.conversationId, text, card)
            }
            session.toolTraceFlow.value = event.planStepOutputs.flatMap { it.toolTrace }
          }
          finishFailed(session, event.message)
        }

        is AgentEvent.StateChanged -> when (event.state) {
          // A new ReAct iteration accumulates text from scratch — clear ONLY the buffer. Do NOT clear the
          // visible bubble: between iterations it shows the tool-call progress, and the first TextDelta
          // of the new iteration overwrites it. This used to also reset the bubble (resetStreaming),
          // which "wiped" the previous iteration's preamble into nothing — it looked like "the answer
          // is being written, then disappears" (only on turns with a tool: without one there is a single
          // iteration and nothing to wipe).
          AgentState.STREAMING -> session.streamBuffer.reset()
          // The agent went off to run a tool: show progress in place of the bubble, not the preamble.
          // Clear the buffer so a late flush does not bring the preamble back over the progress.
          AgentState.EXECUTING_TOOL -> {
            session.streamBuffer.reset()
            session.streamingTextFlow.value = TurnTexts.TOOL_PROGRESS
          }
          // The iteration's stream ended (FINISHED etc.): flush the tail that did not pass the rate gate.
          else -> session.streamBuffer.flush()?.let { session.streamingTextFlow.value = it }
        }

        // Task 10 (structured-plan-engine-907066): PLAN/GOAL terminal events of StructuredPlanOrchestrator
        // — their own text, without resolveCompletedTurn (that one serves only CHAT/REFLECT/TUTOR, which
        // arrive here as AgentEvent.Completed; PLAN/GOAL never come from those modes).
        is AgentEvent.PlanProposed -> finishPlanMessage(session, TurnTexts.planProposedMessage(event.planId, event.version))
        is AgentEvent.PlanProgressed -> {
          // First the step's own answer (the reason the plan was run), then the progress line. Only the
          // progress line used to be written, and the step's result was lost entirely.
          persistPlanStepOutputs(event.result.stepOutputs) { text, card ->
            persistence.addAssistantMessage(session.conversationId, text, card)
          }
          finishPlanMessage(
            session,
            TurnTexts.planProgressMessage(event.result),
            toolTrace = event.result.stepOutputs.flatMap { it.toolTrace },
          )
        }
        is AgentEvent.PlanBlocked -> finishPlanMessage(session, event.reason)

        // Refine the progress with the tool name (EXECUTING_TOOL showed a generic text a moment earlier).
        is AgentEvent.ToolStarted -> session.streamingTextFlow.value = TurnTexts.toolProgress(event.name, event.args)

        // ChatViewModel takes the approval bar from ToolApprovalCoordinator.pending (filtering by its own
        // conversationId), not from the turn's event stream. Here we push a notification when the app
        // is not on screen: otherwise a delete/send request expires with nothing done and nobody asked.
        // The branch is explicit, not else: a new loop event must break compilation here, not get
        // lost silently.
        is AgentEvent.ToolApprovalRequired -> watchPendingApproval(session, event.request)
      }
    }
  }

  /**
   * Pushes "the agent needs your permission" for as long as [request] is waiting and the app is not on
   * screen.
   *
   * A loop, not the single `if (!isInForeground())` this used to be, because the order of events in
   * the real case is the other way round: the user asks for a draft, the request appears while the
   * chat is still in front of them, and only THEN they put the phone down. A one-shot check sees the
   * app on screen, decides no push is needed, and the request lives out its whole timeout with
   * nothing in the shade but «The agent is replying…» — which is exactly what Android 14 showed.
   *
   * Launched in the manager's own [scope] rather than awaited here: [collectTurnEvents] must keep
   * projecting the turn's events while this waits. It cannot spin forever — [PendingApprovals] goes
   * false by the coordinator's own timeout at the latest — but the elapsed bound is kept as well, so a
   * port that lies cannot leave a coroutine running for the life of the process.
   */
  private fun watchPendingApproval(session: TurnSession, request: ToolApprovalRequest) {
    scope.launch {
      var waited = 0L
      while (waited <= ToolApprovalCoordinator.DEFAULT_TIMEOUT_MS && pendingApprovals.isPending(request.requestId)) {
        if (!foreground.isInForeground()) {
          val title = runCatching { persistence.conversationTitle(session.conversationId) }.getOrNull()
          notifier.approvalNeeded(
            session.conversationId,
            title,
            CapabilityNames.call(request.toolName, request.arguments),
          )
          return@launch
        }
        delay(APPROVAL_FOREGROUND_RECHECK_MS)
        waited += APPROVAL_FOREGROUND_RECHECK_MS
      }
    }
  }

  private suspend fun handleCompleted(
    session: TurnSession,
    result: AgentResult,
  ) {
    val convId = session.conversationId
    // All decisions (blank errors, cards) live in a pure policy; here we only apply them: write,
    // update the status, notify. This path (AgentEvent.Completed) serves only CHAT/REFLECT/TUTOR:
    // PLAN/GOAL now end with PlanProposed/PlanProgressed/PlanBlocked/Error (see collectTurnEvents) —
    // Task 15 (structured-plan-engine-907066) removed the mode-dependent branches from
    // resolveCompletedTurn together with the old prompt-only PLAN/GOAL, so it no longer needs mode.
    val outcome = resolveCompletedTurn(result)
    val insertedId = if (outcome.responseText.isNotBlank() || outcome.cardSpecs.isNotEmpty()) {
      persistence.addAssistantMessage(convId, outcome.responseText, outcome.cardSpecs.firstOrNull())
    } else {
      null
    }
    outcome.cardSpecs.drop(1).forEach { spec ->
      persistence.addAssistantMessage(convId, "", spec)
    }

    session.toolTraceFlow.value = result.toolTrace
    // streamingText is NOT reset: the screen keeps the bubble until the message reaches the feed
    // window (replaces pendingHandoffId — the end-of-turn "flicker" does not come back).
    session.statusFlow.value = TurnStatus.Completed(
      insertedMessageId = insertedId,
      responseTimeMs = System.currentTimeMillis() - session.responseStartTime,
      completionTokens = result.completionTokensEstimate,
      contextTokensUsed = result.promptTokensEstimate,
      error = outcome.blankError,
      // "Retry" — when there is a blank-answer error and the user's text is still in the session.
      canRetry = outcome.blankError != null && session.lastUserText != null,
    )
    notifyIfBackground(convId, preview = outcome.responseText.take(140).ifBlank { TurnTexts.TURN_DONE_FALLBACK }, failed = false)
  }

  /**
   * Task 10 (structured-plan-engine-907066): shared tail for [AgentEvent.PlanProposed]/
   * [AgentEvent.PlanProgressed]/[AgentEvent.PlanBlocked] — persists [text] as a plain assistant
   * message (no card yet — a dedicated `agent_plan` PlanCard is Task 12's Plan UI, not this task)
   * and completes the session, mirroring [handleCompleted]'s own "insert, update status, notify"
   * bookkeeping. No token stats: a plan turn's LLM calls happen inside
   * `com.aiopenpocket.agent.core.plan.planner.PlanPlanner`/`com.aiopenpocket.agent.core.plan.execution.PlanExecutor`,
   * not this loop, and are not surfaced here. [toolTrace] — the trace of executed steps (GOAL), so
   * the tools panel shows what a step actually called; it is empty for PLAN/NeedsInfo. Always
   * `canRetry = false` — unlike a blank/network error, there is nothing to retry: the user can just
   * send another PLAN/GOAL turn.
   */
  private suspend fun finishPlanMessage(session: TurnSession, text: String, toolTrace: List<ToolTrace> = emptyList()) {
    val convId = session.conversationId
    val insertedId = persistence.addAssistantMessage(convId, text, cardSpec = null)
    session.toolTraceFlow.value = toolTrace
    session.statusFlow.value = TurnStatus.Completed(
      insertedMessageId = insertedId,
      responseTimeMs = System.currentTimeMillis() - session.responseStartTime,
      completionTokens = 0,
      contextTokensUsed = 0,
      error = null,
      canRetry = false,
    )
    notifyIfBackground(convId, preview = text.take(140).ifBlank { TurnTexts.TURN_DONE_FALLBACK }, failed = false)
  }

  private suspend fun finishFailed(session: TurnSession, message: String) {
    session.statusFlow.value = TurnStatus.Failed(message, canRetry = session.lastUserText != null)
    notifyIfBackground(session.conversationId, preview = message, failed = true)
  }

  /** Push only when the app is really in the background; inside the app — the conversation list badges. */
  private suspend fun notifyIfBackground(conversationId: String, preview: String, failed: Boolean) {
    if (foreground.isInForeground()) return
    val title = runCatching { persistence.conversationTitle(conversationId) }.getOrNull()
    notifier.turnFinished(conversationId, title, preview, failed)
  }

  private fun resetStreaming(session: TurnSession) {
    session.streamBuffer.reset()
    session.streamingTextFlow.value = ""
  }

  companion object {
    private const val TAG = "TurnManager"

    /**
     * How often [watchPendingApproval] asks again whether the app has left the screen. Two seconds:
     * short enough that the push is there by the time the user has put the phone down, long enough
     * that a request waiting out its whole window costs a few hundred cheap checks, not thousands.
     */
    private const val APPROVAL_FOREGROUND_RECHECK_MS = 2_000L
  }
}
