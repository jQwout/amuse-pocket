package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CostStatsTest {

  private fun job(
    id: String,
    modality: GenerationModality = GenerationModality.IMAGE,
    status: JobStatus = JobStatus.DONE,
    cost: Double? = null,
    createdAt: Long = 1_000L,
    model: String = "модель",
  ) = GenerationJob(
    id = id,
    request = GenerationRequest(modality, "промпт", model),
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

  @Test
  fun `average price is per model, not smeared across the whole history`() {
    val jobs = listOf(
      job("a", cost = 0.01, model = "flux"),
      job("b", cost = 0.03, model = "flux"),
      // Дорогое видео другой модели не должно поднимать оценку для flux.
      job("c", GenerationModality.VIDEO, cost = 1.20, model = "veo"),
    )

    assertEquals(0.02, jobs.costEstimate("flux")!!.perRunUsd, 1e-9)
    assertEquals(1.20, jobs.costEstimate("veo")!!.perRunUsd, 1e-9)
  }

  @Test
  fun `no usable history means no price at all, not zero`() {
    val jobs = listOf(
      // Провалившееся денег не стоило, а у TTS цены в ответе нет вовсе: обе записи знанием о цене
      // не являются, и выдавать по ним «$0.0000» значит обещать бесплатную генерацию.
      job("failed", status = JobStatus.FAILED, cost = 0.99, model = "flux"),
      job("no-cost", cost = null, model = "flux"),
    )

    assertNull(jobs.costEstimate("flux"))
    assertNull(emptyList<GenerationJob>().costEstimate("flux"))
    assertNull(jobs.costEstimate("модель, которой ещё не запускали"))
  }

  @Test
  fun `estimate keeps how many jobs it is based on`() {
    val jobs = listOf(
      job("a", cost = 0.01, model = "flux"),
      job("b", cost = 0.03, model = "flux"),
      // Успешное задание без цены в ответе: на столько оценка занижена, и это должно быть видно.
      job("c", cost = null, model = "flux"),
    )

    val estimate = jobs.costEstimate("flux")!!
    assertEquals(2, estimate.samples)
    assertEquals(1, estimate.jobsWithoutCost)
    assertFalse(estimate.single, "две пробы — уже средняя, а не «в прошлый раз стоило столько»")
  }

  @Test
  fun `a single past run is not called an average`() {
    val estimate = listOf(job("only", cost = 0.05, model = "flux")).costEstimate("flux")!!

    assertTrue(estimate.single)
    assertTrue(
      "по одному прошлому запуску" in runEstimateLine(estimate, count = 1),
      "строка обязана сказать, что цена взята с одного раза: иначе догадка выглядит как статистика",
    )
  }

  @Test
  fun `unknown price is said out loud, not silently omitted`() {
    val line = runEstimateLine(estimate = null, count = 2)

    assertTrue("цену узнаем" in line, "молчание про деньги неотличимо от «бесплатно»: $line")
    assertTrue("2 файла" in line, line)
  }

  @Test
  fun `price is multiplied by the number of files`() {
    val estimate = CostEstimate(perRunUsd = 0.0042, samples = 3, jobsWithoutCost = 0)

    assertTrue("$0.0042" in runEstimateLine(estimate, count = 1), runEstimateLine(estimate, 1))
    assertTrue("$0.0168" in runEstimateLine(estimate, count = 4), runEstimateLine(estimate, 4))
  }

  @Test
  fun `file count is declined the Russian way`() {
    val e = CostEstimate(perRunUsd = 0.01, samples = 2, jobsWithoutCost = 0)

    assertTrue("1 файл ·" in runEstimateLine(e, 1), runEstimateLine(e, 1))
    assertTrue("2 файла" in runEstimateLine(e, 2), runEstimateLine(e, 2))
    assertTrue("5 файлов" in runEstimateLine(e, 5), runEstimateLine(e, 5))
    // Одиннадцать-четырнадцать — исключение из правила «оканчивается на 2..4».
    assertTrue("12 файлов" in runEstimateLine(e, 12), runEstimateLine(e, 12))
    assertTrue("22 файла" in runEstimateLine(e, 22), runEstimateLine(e, 22))
  }

  @Test
  fun `money is formatted the same everywhere, regardless of system locale`() {
    // Точка, а не запятая: на русской локали «%.4f» иначе даёт «$0,0042».
    assertEquals("$0.0042", formatUsd(0.0042))
    assertEquals("—", formatUsd(null))
  }
}
