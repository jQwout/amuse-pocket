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
 *
 * [exposeExceptionMessage] = false — в баннер всегда [defaultErrorMessage], а `e.message` только в
 * лог. Так у плановых ходов: там бросает сам движок (require/check в PlanRepository.saveProposal,
 * PlanExecutor и т.п.) с английской диагностикой и id планов — пользователю это ничего не говорит.
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
