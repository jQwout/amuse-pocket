package com.aiopenpocket.agent.core.llm.usage

// @trace streaming-llm-chat-6595a1

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Одна строка учёта — один завершившийся вызов модели (см. `LlmCallUsage`).
 *
 * Таблица только пополняется и читается сводками; строки не обновляются и не удаляются по одной.
 * Поэтому суррогатный автоключ, а не составной: естественного ключа у события «вызов случился» нет,
 * два одинаковых вызова подряд — это два разных факта, и склеивать их нельзя.
 *
 * [conversationId] и [agentId] заполняются ПО ВОЗМОЖНОСТИ, из контекста хода. `null` — не пробел в
 * данных, а содержательный ответ: так выглядят вызовы вне хода (аннотатор диалога, извлечение
 * профиля), и доля таких строк как раз и отвечает на вопрос «сколько съедает фон».
 */
@Entity(
  tableName = "llm_usage",
  indices = [Index("at"), Index("conversationId")],
)
data class LlmUsageEntity(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** Момент завершения вызова, epoch millis. */
  val at: Long,
  val provider: String,
  val model: String,
  val conversationId: String?,
  val agentId: String?,
  val promptTokens: Int,
  val completionTokens: Int,
  val costUsd: Double?,
  val estimated: Boolean,
  val hadTools: Boolean,
)

/** Свод по всем вызовам за период. Отдельный тип — Room не умеет возвращать кортеж. */
data class LlmUsageTotals(
  val calls: Int,
  val promptTokens: Long,
  val completionTokens: Long,
  /** `null`, когда цену не назвал ни один вызов периода (провайдер отдал только токены). */
  val costUsd: Double?,
  /** Сколько строк периода посчитаны оценкой, а не замером — мера доверия к сводке. */
  val estimatedCalls: Int,
)

/** Тот же свод, но в разрезе модели: где именно горят деньги. */
data class LlmUsageByModel(
  val model: String,
  val calls: Int,
  val promptTokens: Long,
  val completionTokens: Long,
  val costUsd: Double?,
)
