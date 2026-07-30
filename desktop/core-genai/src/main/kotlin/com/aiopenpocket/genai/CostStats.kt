package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

/**
 * Сводка расходов.
 *
 * [jobsWithoutCost] существует не для симметрии: не каждый ответ провайдера несёт `usage.cost`
 * (у TTS его в теле нет вовсе — там сырой mp3). Поэтому «потрачено» — это нижняя оценка, и UI
 * обязан показывать, по скольким заданиям стоимость неизвестна. Молча складывать только то, что
 * пришло, и выдавать сумму за полную — значит врать про деньги.
 */
data class CostSummary(
  val totalUsd: Double,
  val byModality: Map<GenerationModality, Double>,
  val jobsWithCost: Int,
  val jobsWithoutCost: Int,
) {
  val jobsCounted: Int get() = jobsWithCost + jobsWithoutCost

  /** Сумма неполна — часть заданий не сообщила стоимость. */
  val partial: Boolean get() = jobsWithoutCost > 0

  companion object {
    val EMPTY = CostSummary(0.0, emptyMap(), 0, 0)
  }
}

/**
 * Считает по успешным заданиям: провалившееся задание денег не стоит, а незавершённое ещё не
 * сообщило стоимость. [sinceEpochMs] — граница «за сессию» (null — за всё время).
 */
fun List<GenerationJob>.costSummary(sinceEpochMs: Long? = null): CostSummary {
  val considered = filter { it.status == JobStatus.DONE }
    .filter { sinceEpochMs == null || it.createdAtEpochMs >= sinceEpochMs }
  if (considered.isEmpty()) return CostSummary.EMPTY

  val byModality = considered
    .filter { it.costUsd != null }
    .groupBy { it.request.modality }
    .mapValues { (_, jobs) -> jobs.sumOf { it.costUsd ?: 0.0 } }

  return CostSummary(
    totalUsd = byModality.values.sum(),
    byModality = byModality,
    jobsWithCost = considered.count { it.costUsd != null },
    jobsWithoutCost = considered.count { it.costUsd == null },
  )
}
