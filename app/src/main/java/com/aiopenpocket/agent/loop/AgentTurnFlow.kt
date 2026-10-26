package com.aiopenpocket.agent.loop

// @trace streaming-llm-chat-6595a1

import com.aiopenpocket.agent.core.AgentLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/**
 * The common skeleton of an orchestrator turn — one error contract for all of them: a thrown exception
 * (assemble, serialization, a DB write) becomes an in-band [AgentEvent.Error] rather than a raw throw
 * from the flow; cancellation (Stop / a new turn) is the normal path and is rethrown for cancelAndJoin.
 * The buffer is unlimited, the work runs on IO.
 *
 * [exposeExceptionMessage] = false — the banner always shows [defaultErrorMessage], and `e.message`
 * goes only to the log. That is the case for plan turns: there the engine itself throws
 * (require/check in PlanRepository.saveProposal, PlanExecutor etc.) with English diagnostics and plan
 * ids — which tell the user nothing.
 */
internal fun agentTurnFlow(
  log: AgentLog,
  tag: String,
  defaultErrorMessage: String,
  exposeExceptionMessage: Boolean = true,
  body: suspend ProducerScope<AgentEvent>.() -> Unit,
): Flow<AgentEvent> = channelFlow {
  try {
    body()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.e(tag, "✗ turn failed", e)
    send(AgentEvent.StateChanged(AgentState.ERROR))
    val message = if (exposeExceptionMessage) e.message ?: defaultErrorMessage else defaultErrorMessage
    send(AgentEvent.Error(message))
  }
}.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
