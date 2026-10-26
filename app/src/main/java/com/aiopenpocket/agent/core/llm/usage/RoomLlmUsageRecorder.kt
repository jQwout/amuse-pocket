package com.aiopenpocket.agent.core.llm.usage

// @trace streaming-llm-chat-6595a1

import android.util.Log
import com.aiopenpocket.agent.core.llm.LlmCallUsage
import com.aiopenpocket.agent.core.llm.LlmUsageRecorder
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Spend accounting in Room, tied to the turn.
 *
 * The conversation and the agent come from [TurnContextHolder], not from arguments: [LlmCallUsage]
 * comes from `OpenAiCompatClient`, which knows nothing about turns and should not — it sees a request
 * and an answer. The context holder is already carried through the coroutine context for exactly such
 * cases (tools read it the same way), and it honestly returns `null` where there is no turn.
 *
 * **A write error does not escape.** Accounting is a side task next to the answer to the user: a
 * failed insert (disk full, the DB being recreated after a destructive migration) must not cut the
 * stream off mid-word. Hence runCatching and a log line — staying completely silent is not an option
 * either, or lost statistics would go unnoticed.
 */
@SingleIn(AppScope::class)
@Inject
class RoomLlmUsageRecorder(
  private val dao: LlmUsageDao,
  private val turnContext: TurnContextHolder,
) : LlmUsageRecorder {

  override suspend fun record(usage: LlmCallUsage) {
    runCatching {
      dao.insert(
        LlmUsageEntity(
          at = System.currentTimeMillis(),
          provider = usage.provider.name,
          model = usage.model,
          conversationId = turnContext.conversationId,
          agentId = turnContext.agentId,
          promptTokens = usage.promptTokens,
          completionTokens = usage.completionTokens,
          costUsd = usage.costUsd,
          estimated = usage.estimated,
          hadTools = usage.hadTools,
        ),
      )
    }.onFailure { Log.w(TAG, "не записал расход ${usage.model}: ${it.message}") }
  }

  private companion object {
    const val TAG = "LlmUsage"
  }
}
