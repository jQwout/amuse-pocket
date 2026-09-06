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
 * Учёт расхода в Room, с привязкой к ходу.
 *
 * Диалог и агент берутся из [TurnContextHolder], а не из аргументов: [LlmCallUsage] приходит из
 * `OpenAiCompatClient`, который про ходы ничего не знает и знать не должен — он видит запрос и
 * ответ. Держатель контекста уже прокинут через coroutine-context ровно для таких случаев (так же
 * его читают тулы), и он честно отдаёт `null` там, где хода нет.
 *
 * **Ошибка записи не выходит наружу.** Учёт — побочная задача рядом с ответом пользователю: упавший
 * insert (диск полон, база пересоздаётся после разрушительной миграции) не должен обрывать стрим на
 * полуслове. Поэтому runCatching и запись в лог — молчать совсем тоже нельзя, иначе пропажа
 * статистики останется незамеченной.
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
