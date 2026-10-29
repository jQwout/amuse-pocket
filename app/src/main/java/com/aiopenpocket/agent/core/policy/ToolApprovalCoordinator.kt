package com.aiopenpocket.agent.core.policy

// @trace user-agent-builder-848153
// @trace approval-outcome-honesty-554a8c

import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * App-wide gate between a [PolicyDecision.AwaitApproval] and the human who has to answer it.
 * [com.aiopenpocket.agent.loop.ReactLoopRunner] calls [awaitApproval] and suspends; the chat UI
 * (`ChatViewModel`) observes [pending], shows a modal/bar, and calls [resolve] with the user's choice.
 *
 * Backed by a plain [ConcurrentHashMap] rather than a `Mutex` on purpose: every mutating operation here
 * (register/complete/remove) is a single atomic map op or a [MutableStateFlow.update] (itself a
 * lock-free CAS loop), so [resolve]/[abandonAllPending]/[cancelRun] can all stay ordinary,
 * non-suspending functions callable from anywhere (a ViewModel intent handler, a future lifecycle
 * callback) without needing a `CoroutineScope` of their own.
 *
 * "No answer is never a hang and never an exception" is enforced by [awaitApproval]'s own [timeoutMs] —
 * the turn asked, nobody answered, move on. But "nobody answered" is reported as
 * [ApprovalOutcome.UNANSWERED], NOT as a denial: this function used to return a bare `Boolean`, the
 * timeout and the "Decline" button both came back `false`, and the model — handed one text covering
 * both — told the user "declined in chat" about a card they had never seen (see [ApprovalOutcome]).
 * A cancelled ENCLOSING run (TurnManager `cancel()`, "Stop") needs nothing extra: cancelling the
 * coroutine that is suspended inside [awaitApproval] throws
 * [kotlinx.coroutines.CancellationException] through it as normal structured-concurrency teardown —
 * the `finally` below still runs and removes the bookkeeping, it just doesn't force a value into a
 * deferred nobody will read.
 *
 * [abandonAllPending]/[cancelRun] are ready-made "stop waiting on everything at once" levers, but
 * nothing in production calls them today (only tests): going to the background must NOT resolve a
 * request — the turn keeps living, a push now tells the user it is waiting
 * ([com.aiopenpocket.agent.loop.TurnManager]), and they can come back and answer.
 */
@SingleIn(AppScope::class)
@Inject
class ToolApprovalCoordinator() {

  private class Entry(val request: ToolApprovalRequest, val deferred: CompletableDeferred<ApprovalOutcome>)

  private val byRequestId = ConcurrentHashMap<String, Entry>()

  private val pendingFlow = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())

  /** Every approval currently awaiting a decision, across every run/conversation — callers filter by [ToolApprovalRequest.conversationId]. */
  val pending: StateFlow<List<ToolApprovalRequest>> = pendingFlow.asStateFlow()

  /**
   * Registers [request] into [pending] and suspends until [resolve] completes it, [timeoutMs] elapses,
   * or [abandonAllPending]/[cancelRun] force it. [ApprovalOutcome.DECLINED] comes ONLY from a
   * [resolve] with `approved = false`, i.e. from a person who pressed the button; every other way of
   * not getting a "yes" — the timeout, a forced teardown, a plain miss — is
   * [ApprovalOutcome.UNANSWERED]. This function never throws for "no approval arrived" (only real
   * coroutine cancellation propagates, see class doc). Always removes [request] from [pending] again,
   * on every exit path.
   */
  suspend fun awaitApproval(
    request: ToolApprovalRequest,
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    // Called once the request is registered, and only then: whoever learns of the request from here on —
    // the chat bar, TurnManager's push watcher — finds it pending. Announcing it before registering let a
    // watcher that ran first see "not pending", take it for "already answered" and never push (Android 14,
    // 29 Oct 2026), and an "Allow" tapped in that gap had nothing to resolve.
    onPending: suspend () -> Unit = {},
  ): ApprovalOutcome {
    val deferred = CompletableDeferred<ApprovalOutcome>()
    byRequestId[request.requestId] = Entry(request, deferred)
    pendingFlow.update { it + request }
    return try {
      onPending()
      withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ApprovalOutcome.UNANSWERED
    } finally {
      remove(request.requestId)
    }
  }

  /** Is [requestId] still waiting for an answer right now? The "should we nudge the user?" question, asked from outside the suspended turn (see [com.aiopenpocket.agent.loop.PendingApprovals]). */
  fun isPending(requestId: String): Boolean = byRequestId.containsKey(requestId)

  /**
   * The user's decision for one pending [requestId]. [runId] must match the run that created the
   * request — a stale UI reference from an earlier, already-finished run of the same conversation (or
   * any other run) can never resolve someone else's pending approval; it just gets `false` back with no
   * effect on the actual entry. Resolving an already-resolved/unknown id is likewise a harmless `false`.
   */
  fun resolve(runId: String, requestId: String, approved: Boolean): Boolean {
    val entry = byRequestId[requestId] ?: return false
    if (entry.request.runId != runId) return false
    val completed = entry.deferred.complete(
      if (approved) ApprovalOutcome.APPROVED else ApprovalOutcome.DECLINED,
    )
    remove(requestId)
    return completed
  }

  /** Every request for [runId] stops waiting as [ApprovalOutcome.UNANSWERED] — for a caller that tears a run down without cancelling its coroutine (no production caller today, see class doc). */
  fun cancelRun(runId: String) {
    byRequestId.values.filter { it.request.runId == runId }
      .forEach { it.deferred.complete(ApprovalOutcome.UNANSWERED) }
  }

  /**
   * Every currently pending request, from any run, stops waiting as [ApprovalOutcome.UNANSWERED] (no
   * production caller today, see class doc). Named "abandon", not "deny": whatever makes the app give
   * up on a request, it is not the user saying no, and a lever named after a denial is an invitation
   * to report one.
   */
  fun abandonAllPending() {
    byRequestId.values.toList().forEach { it.deferred.complete(ApprovalOutcome.UNANSWERED) }
  }

  private fun remove(requestId: String) {
    byRequestId.remove(requestId)
    pendingFlow.update { list -> list.filterNot { it.requestId == requestId } }
  }

  companion object {
    /**
     * How long a request waits for a human before the turn gives up on it as
     * [ApprovalOutcome.UNANSWERED]. Ten minutes, raised from two on 28 Oct 2026: two minutes was sized
     * for someone looking at the chat, and the whole point of the push is that they are not — a person
     * who hears a notification may be in another room, and a window that expires before they reach the
     * phone turns the confirmation into theatre. It still has to end: a turn holds the conversation
     * (the next message cancels it) and a model's context while it waits. Ten minutes is far past any
     * real "coming, one second" and nowhere near forever. In a backgrounded app something shorter
     * usually bites first — the `shortService` protection lasts about three minutes, after which the
     * turn fails with "Retry" — and that is fine: a turn the system stopped is an honest answer, while
     * a refusal the user never made is not.
     */
    const val DEFAULT_TIMEOUT_MS = 600_000L
  }
}
