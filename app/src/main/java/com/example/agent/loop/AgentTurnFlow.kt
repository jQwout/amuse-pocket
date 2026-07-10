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
 * Общий скелет хода оркестратора — единый error-контракт для всех: брошенное исключение (assemble,
 * сериализация, запись в БД) становится in-band [AgentEvent.Error], а не raw throw из flow;
 * отмена (Stop / новый ход) — штатный путь и пробрасывается для cancelAndJoin. Буфер безлимитный,
 * работа — на IO.
 */
internal fun agentTurnFlow(
  log: AgentLog,
  tag: String,
  defaultErrorMessage: String,
  body: suspend ProducerScope<AgentEvent>.() -> Unit,
): Flow<AgentEvent> = channelFlow {
  try {
    body()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.e(tag, "✗ turn failed", e)
    send(AgentEvent.StateChanged(AgentState.ERROR))
    send(AgentEvent.Error(e.message ?: defaultErrorMessage))
  }
}.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
