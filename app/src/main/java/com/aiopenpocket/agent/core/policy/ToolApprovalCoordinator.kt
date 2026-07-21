package com.aiopenpocket.agent.core.policy

// @trace user-agent-builder-848153

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
 * lock-free CAS loop), so [resolve]/[denyAllPending]/[cancelRun] can all stay ordinary, non-suspending
 * functions callable from anywhere (a ViewModel intent handler, a future lifecycle callback) without
 * needing a `CoroutineScope` of their own.
 *
 * "Approval without an answer = deny" (never a hang, never an exception) is enforced two ways:
 *  - [awaitApproval]'s own [timeoutMs] — the turn asked, nobody answered, move on;
 *  - [denyAllPending] — for a caller like an app-backgrounded signal that needs to resolve every
 *    currently-open request at once, not just one run's.
 * A cancelled ENCLOSING run (TurnManager `cancel()`) doesn't go through either path: cancelling the
 * coroutine that is suspended inside [awaitApproval] throws [kotlinx.coroutines.CancellationException]
 * through it as normal structured-concurrency teardown — the `finally` below still runs and removes the
 * bookkeeping, it just doesn't force a `false` value into a deferred nobody will read.
 */
@SingleIn(AppScope::class)
@Inject
class ToolApprovalCoordinator() {

  private class Entry(val request: ToolApprovalRequest, val deferred: CompletableDeferred<Boolean>)

  private val byRequestId = ConcurrentHashMap<String, Entry>()

  private val pendingFlow = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())

  /** Every approval currently awaiting a decision, across every run/conversation — callers filter by [ToolApprovalRequest.conversationId]. */
  val pending: StateFlow<List<ToolApprovalRequest>> = pendingFlow.asStateFlow()

  /**
   * Registers [request] into [pending] and suspends until [resolve] completes it, [timeoutMs] elapses,
   * or [denyAllPending]/[cancelRun] force it — all of those (and a plain miss) resolve to `false`, this
   * function never throws for "no approval arrived" (only real coroutine cancellation propagates, see
   * class doc). Always removes [request] from [pending] again, on every exit path.
   */
  suspend fun awaitApproval(request: ToolApprovalRequest, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
    val deferred = CompletableDeferred<Boolean>()
    byRequestId[request.requestId] = Entry(request, deferred)
    pendingFlow.update { it + request }
    return try {
      withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
    } finally {
      remove(request.requestId)
    }
  }

  /**
   * The user's decision for one pending [requestId]. [runId] must match the run that created the
   * request — a stale UI reference from an earlier, already-finished run of the same conversation (or
   * any other run) can never resolve someone else's pending approval; it just gets `false` back with no
   * effect on the actual entry. Resolving an already-resolved/unknown id is likewise a harmless `false`.
   */
  fun resolve(runId: String, requestId: String, approved: Boolean): Boolean {
    val entry = byRequestId[requestId] ?: return false
    if (entry.request.runId != runId) return false
    val completed = entry.deferred.complete(approved)
    remove(requestId)
    return completed
  }

  /** Every request for [runId] resolves to deny — used when that run is cancelled/torn down so none of its approvals are left dangling for a screen that will never come back to them. */
  fun cancelRun(runId: String) {
    byRequestId.values.filter { it.request.runId == runId }.forEach { it.deferred.complete(false) }
  }

  /** Every currently pending request, from any run, resolves to deny — e.g. the app going to background with no one left to answer. */
  fun denyAllPending() {
    byRequestId.values.toList().forEach { it.deferred.complete(false) }
  }

  private fun remove(requestId: String) {
    byRequestId.remove(requestId)
    pendingFlow.update { list -> list.filterNot { it.requestId == requestId } }
  }

  companion object {
    /** Nobody answered in two minutes — treat it the same as an explicit deny (recoverable, the model can just ask again). */
    const val DEFAULT_TIMEOUT_MS = 120_000L
  }
}
