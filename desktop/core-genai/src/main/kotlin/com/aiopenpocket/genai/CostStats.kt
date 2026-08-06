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

/**
 * Оценка цены запуска по собственной истории.
 *
 * Отдельный тип, а не голый `Double`, ровно по той же причине, по которой у [CostSummary] есть
 * [CostSummary.partial]: число без указания, из чего оно получено, врёт молча. «≈ $0.0042» по
 * одному-единственному прошлому запуску и то же «≈ $0.0042» по дюжине выглядят одинаково, хотя
 * доверия заслуживают совсем разного.
 */
data class CostEstimate(
  /**
   * Средняя цена одного ЗАПУСКА у этой модели (задания, а не файла: задание может сохранить
   * несколько выходов). Для картинок это одно и то же, но называть надо тем, что считается.
   */
  val perRunUsd: Double,
  /** По скольким заданиям посчитано. 1 — это не средняя, а «в прошлый раз стоило столько». */
  val samples: Int,
  /** Сколько завершённых заданий этой модели цену не сообщили — на столько оценка занижена. */
  val jobsWithoutCost: Int,
) {
  /** Оценка по одному разу: называть её средней нельзя. */
  val single: Boolean get() = samples == 1
}

/**
 * Во что обходился один файл у этой модели в прошлые разы — единственная честная оценка цены ДО
 * запуска.
 *
 * Точную цену кадра до запуска назвать нельзя, и это не «прайса нет». Прайс в `/api/v1/models` как
 * раз есть — `pricing.image_output` у каждой картиночной модели, — но он **за токен**, а сколько
 * токенов стоит один кадр, каталог не публикует: у разных моделей это разные сотни токенов, и число
 * зависит от размера результата, который мы узнаём только постфактум. Умножить ставку на выдуманное
 * количество токенов и назвать это ценой — то же «≈ 24 кредита», только с видом точного расчёта.
 * Средняя по собственной истории честнее: это то, что уже списали с этого самого счёта.
 *
 * У ВИДЕО ситуация другая и лучше: `/api/v1/videos/models` отдаёт `pricing_skus`
 * (`cents_per_second_output`), а длительность выбирает пользователь — там точная смета возможна.
 * Сейчас неактуально, потому что видео из UI недостижимо, но менять эту функцию под видео, когда
 * оно вернётся, не придётся: у него будет свой расчёт, а не средняя.
 *
 * Считается по МОДЕЛИ, а не по модальности: между flux и gemini разница в разы, и усреднять их
 * вместе — то же самое враньё, только аккуратнее выглядящее.
 *
 * Оговорка, которую оценка не закрывает: провалившееся задание цены не несёт (`GenerationProgress`
 * отдаёт её только на успехе), а провайдер за отклонённый контент-фильтром запрос деньги всё же
 * берёт. То есть реальный расход ВЫШЕ оценки, и обещать обратное нельзя.
 *
 * null — по модели ещё не было ни одного завершённого задания с известной ценой. Тогда UI обязан
 * сказать «не знаем», а не промолчать и не подставить цену соседней модели.
 */
fun List<GenerationJob>.costEstimate(model: String): CostEstimate? {
  val finished = filter { it.status == JobStatus.DONE && it.request.model == model }
  val known = finished.mapNotNull { it.costUsd }
  if (known.isEmpty()) return null
  return CostEstimate(
    perRunUsd = known.average(),
    samples = known.size,
    jobsWithoutCost = finished.size - known.size,
  )
}

/**
 * Строка под кнопкой запуска: сколько файлов получится и во что это обойдётся.
 *
 * Живёт в ядре, а не в экране, потому что это работа с деньгами: у `:app-desktop` тестов нет вовсе,
 * и умножение на количество, округление и склонение остались бы непроверенными.
 */
fun runEstimateLine(estimate: CostEstimate?, count: Int): String {
  // Склонение общее с окном выбора модели: два одинаковых правила в двух файлах разъезжаются
  // ровно тогда, когда одно из них правят.
  val files = "$count " + pluralRu(count, "файл", "файла", "файлов")

  if (estimate == null) return "$files · цену узнаем после первого запуска"

  val price = "≈ " + formatUsd(estimate.perRunUsd * count)
  val basis = when {
    estimate.single -> "по одному прошлому запуску"
    estimate.jobsWithoutCost > 0 -> "по ${estimate.samples} из ${estimate.samples + estimate.jobsWithoutCost}"
    else -> "по ${estimate.samples} прошлым"
  }
  return "$price · $files · $basis"
}

/**
 * Доллары с четырьмя знаками. Генерации бывают дешевле цента, и округление до двух показывало бы
 * «$0.00».
 *
 * `Locale.ROOT`: иначе на русской системе выходит «$0,0042» — точка и запятая вперемешку
 * в зависимости от машины.
 */
fun formatUsd(costUsd: Double?): String =
  costUsd?.let { "$" + "%.4f".format(java.util.Locale.ROOT, it) } ?: "—"
