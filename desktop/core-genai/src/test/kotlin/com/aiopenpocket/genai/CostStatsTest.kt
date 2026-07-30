package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CostStatsTest {

  private fun job(
    id: String,
    modality: GenerationModality = GenerationModality.IMAGE,
    status: JobStatus = JobStatus.DONE,
    cost: Double? = null,
    createdAt: Long = 1_000L,
  ) = GenerationJob(
    id = id,
    request = GenerationRequest(modality, "промпт", "модель"),
    createdAtEpochMs = createdAt,
    status = status,
    costUsd = cost,
  )

  @Test
  fun `costs add up per modality`() {
    val summary = listOf(
      job("a", GenerationModality.IMAGE, cost = 0.01),
      job("b", GenerationModality.IMAGE, cost = 0.02),
      job("c", GenerationModality.VIDEO, cost = 0.25),
    ).costSummary()

    assertEquals(0.28, summary.totalUsd, 1e-9)
    assertEquals(0.03, summary.byModality.getValue(GenerationModality.IMAGE), 1e-9)
    assertEquals(0.25, summary.byModality.getValue(GenerationModality.VIDEO), 1e-9)
    assertFalse(summary.partial)
  }

  @Test
  fun `jobs without a reported cost are counted, not silently dropped`() {
    val summary = listOf(
      job("a", cost = 0.01),
      // TTS не отдаёт usage.cost — в теле сырой mp3. Такое задание обязано быть видно в сводке.
      job("b", GenerationModality.AUDIO, cost = null),
    ).costSummary()

    assertEquals(0.01, summary.totalUsd, 1e-9)
    assertEquals(1, summary.jobsWithCost)
    assertEquals(1, summary.jobsWithoutCost)
    assertEquals(2, summary.jobsCounted)
    assertTrue(summary.partial, "сумма неполна — это должно быть видно вызывающему")
  }

  @Test
  fun `only finished jobs count`() {
    val summary = listOf(
      job("done", cost = 0.01),
      job("failed", status = JobStatus.FAILED, cost = 0.99),
      job("running", status = JobStatus.RUNNING),
      job("cancelled", status = JobStatus.CANCELLED, cost = 0.99),
    ).costSummary()

    assertEquals(0.01, summary.totalUsd, 1e-9)
    assertEquals(1, summary.jobsCounted)
  }

  @Test
  fun `session boundary cuts off older jobs`() {
    val jobs = listOf(
      job("old", cost = 1.0, createdAt = 1_000L),
      job("new", cost = 2.0, createdAt = 5_000L),
    )

    assertEquals(3.0, jobs.costSummary().totalUsd, 1e-9)
    assertEquals(2.0, jobs.costSummary(sinceEpochMs = 5_000L).totalUsd, 1e-9)
  }

  @Test
  fun `empty history gives an empty summary, not a crash`() {
    val summary = emptyList<GenerationJob>().costSummary()
    assertEquals(0.0, summary.totalUsd)
    assertEquals(0, summary.jobsCounted)
    assertFalse(summary.partial)
  }
}
