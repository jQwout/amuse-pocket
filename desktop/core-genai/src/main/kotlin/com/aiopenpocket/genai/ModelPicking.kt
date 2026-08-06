package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

/**
 * Отбор, порядок и подписи в окне выбора модели.
 *
 * Живёт в ядре, а не в экране, по той же причине, что и `runEstimateLine`: у `:app-desktop` тестов
 * нет вовсе, а здесь считаются деньги, склоняются числительные и решается, какая модель подходит под
 * уже набранный запрос. В экране остаётся Compose и ничего больше.
 *
 * Главное правило всего файла: **незаявленное — не то же самое, что запрещённое**. Модель, которая
 * не перечислила соотношения сторон, не «не умеет 4:3» — она про это молчит, и записать её в
 * неподходящие значило бы спрятать рабочий вариант на основании собственной догадки. Поэтому
 * несовпадение считается только там, где список непустой и выбранного в нём нет.
 */

/** Что уже набрано в форме генерации. По нему окно решает, что показать сверху. */
data class PickerContext(
  /** Сколько файлов прикреплено. */
  val attachments: Int = 0,
  val aspectRatio: String? = null,
  /** Тир разрешения (`1K`, `2K`, `4K`). */
  val resolution: String? = null,
) {
  val isEmpty: Boolean get() = attachments == 0 && aspectRatio == null && resolution == null
}

/**
 * Строка списка: запись каталога плюс всё, что мы о ней знаем со стороны — цена от провайдера,
 * собственная история запусков, избранное и совпадение с текущим запросом.
 */
data class ModelCard(
  val entry: GenaiModelEntry,
  val price: ModelPrice,
  /** Сколько раз пользователь запускал эту модель. 0 — ни разу. */
  val runs: Int,
  /** Средняя цена запуска по своей истории. null — цену ни один запуск не сообщил. */
  val averageUsd: Double?,
  val favorite: Boolean,
  /** null — под текущий запрос годится. Иначе — чем именно не годится, словами. */
  val misfit: String?,
) {
  val id: String get() = entry.id
  val fits: Boolean get() = misfit == null
}

enum class PickerSort(val label: String) {
  RECOMMENDED("Рекомендуемые"),
  CHEAPEST("Дешевле"),
  NEWEST("Новые"),
}

/**
 * Собирает строки списка. История передаётся целиком: считать по ней и запуски, и среднюю цену
 * дешевле, чем тащить в окно два готовых среза, которые обязаны быть согласованы между собой.
 */
fun buildCards(
  entries: List<GenaiModelEntry>,
  jobs: List<GenerationJob>,
  favorites: Set<String>,
  context: PickerContext = PickerContext(),
): List<ModelCard> {
  val runsById = jobs.groupingBy { it.request.model }.eachCount()
  return entries.map { entry ->
    ModelCard(
      entry = entry,
      price = entry.pricing.modelPrice(),
      // Считаем ВСЕ запуски, а не только успешные: «вы запускали 22 раза» — это про то, знаком ли
      // пользователь с моделью, а не про то, сколько кадров уцелело.
      runs = runsById[entry.id] ?: 0,
      averageUsd = jobs.costEstimate(entry.id)?.perRunUsd,
      favorite = entry.id in favorites,
      misfit = misfitReason(entry, context),
    )
  }
}

/**
 * Чем модель не подходит под набранный запрос. null — подходит либо сказать нечего.
 *
 * Порядок проверок — по цене ошибки: лишний референс провайдер отвергает жёстче всего, и это первое,
 * что стоит сказать.
 */
fun misfitReason(entry: GenaiModelEntry, context: PickerContext): String? {
  val caps = entry.image ?: return null
  // maxReferences == 0 — это «не заявлено», а не «не берёт»: у живых моделей поле есть всегда, и
  // трактовать его отсутствие как отказ значило бы прятать модель по догадке.
  if (context.attachments > 0 && caps.maxReferences > 0 && context.attachments > caps.maxReferences) {
    return "берёт до ${caps.maxReferences} ${referenceWord(caps.maxReferences)}"
  }
  if (context.aspectRatio != null && caps.aspectRatios.isNotEmpty() &&
    context.aspectRatio !in caps.aspectRatios
  ) {
    return "не заявляет ${context.aspectRatio}"
  }
  if (context.resolution != null && caps.resolutions.isNotEmpty() &&
    context.resolution !in caps.resolutions
  ) {
    return "не заявляет ${context.resolution}"
  }
  return null
}

/**
 * Порядок в списке. [tier] — выбранный тир разрешения: у моделей с ценой по тирам «дешевле» зависит
 * от него, и сортировать по базовой цене там, где пользователь уже выбрал 4K, значит показать
 * порядок, которого не будет в счёте.
 */
fun sortCards(cards: List<ModelCard>, sort: PickerSort, tier: String? = null): List<ModelCard> =
  when (sort) {
    PickerSort.RECOMMENDED -> cards.sortedBy { it.entry.priority }
    // Несравнимая цена (за мегапиксель, по токенам, нет прайса) уезжает вниз общей пачкой, а не
    // встаёт среди дешёвых: см. ModelPrice.frameUsd.
    PickerSort.CHEAPEST -> cards.sortedWith(
      compareBy<ModelCard> { it.price.frameUsd(tier) == null }
        .thenBy { it.price.frameUsd(tier) ?: Double.MAX_VALUE }
        .thenBy { it.entry.priority },
    )
    PickerSort.NEWEST -> cards.sortedWith(
      compareByDescending<ModelCard> { it.entry.created }.thenBy { it.entry.priority },
    )
  }

/** Подходит ли строка под поиск. Ищем и по описанию: то, что человек видит на экране, обязано находиться. */
fun ModelCard.matches(query: String): Boolean {
  if (query.isBlank()) return true
  return entry.id.contains(query, ignoreCase = true) ||
    entry.displayName.contains(query, ignoreCase = true) ||
    entry.vendor.contains(query, ignoreCase = true) ||
    entry.description.contains(query, ignoreCase = true)
}

/**
 * Короткие пилюли под именем модели: только то, что модель заявила о себе сама.
 *
 * Ни «лицо», ни «текст в кадре» здесь нет и быть не может — таких полей в API не существует, и
 * написать их значило бы выдать редакторскую догадку за характеристику.
 */
fun capabilityTags(entry: GenaiModelEntry): List<String> {
  val caps = entry.image ?: return emptyList()
  return buildList {
    if (caps.maxReferences > 0) add("реф. до ${caps.maxReferences}")
    topResolution(caps.resolutions)?.let { add("до $it") }
    if (caps.outputFormats.isNotEmpty()) add(caps.outputFormats.joinToString("/"))
  }
}

/**
 * Фраза «что умеет» для панели деталей — собрана из тех же заявленных параметров.
 *
 * Это замена «почему предлагаем» из макета: обещать «держит лицо по референсу» неоткуда, а
 * пересказать своими словами то, что модель сама о себе сообщила, — можно и полезно.
 */
fun capabilitySentence(entry: GenaiModelEntry): String {
  val caps = entry.image ?: return "Модель не сообщила о себе параметров — уйдёт только промпт."
  val parts = buildList {
    if (caps.maxReferences > 0) add("берёт до ${caps.maxReferences} ${referenceWord(caps.maxReferences)}")
    topResolution(caps.resolutions)?.let { add("отдаёт до $it") }
    if (caps.aspectRatios.isNotEmpty()) {
      add("${caps.aspectRatios.size} ${pluralRu(caps.aspectRatios.size, "формат", "формата", "форматов")} кадра")
    }
    if (caps.outputFormats.isNotEmpty()) add("сохраняет в ${caps.outputFormats.joinToString(" и ")}")
    if (caps.qualities.isNotEmpty()) add("уровни качества: ${caps.qualities.joinToString(", ")}")
  }
  if (parts.isEmpty()) return "Модель не сообщила о себе параметров — уйдёт только промпт."
  return parts.joinToString(", ").replaceFirstChar { it.uppercase() } + "."
}

/** Строка баннера: что именно из формы учитывается. Пусто, если учитывать нечего. */
fun contextSummary(context: PickerContext): String = buildList {
  if (context.attachments > 0) {
    add("${context.attachments} ${pluralRu(context.attachments, "файл", "файла", "файлов")}")
  }
  context.aspectRatio?.let { add(it) }
  context.resolution?.let { add(it) }
}.joinToString(", ")

/**
 * Старший тир из заявленных: `["512","1K","2K","4K"]` → `4K`. null — тиров не заявлено.
 *
 * Сравниваем по разобранному числу, а не по порядку в списке и не по алфавиту: порядок провайдер не
 * обещал, а по алфавиту «512» больше «4K». Незнакомую строку пропускаем — гадать о её месте не о чем.
 */
fun topResolution(resolutions: List<String>): String? =
  resolutions.mapNotNull { tier -> resolutionRank(tier)?.let { tier to it } }.maxByOrNull { it.second }?.first

private fun resolutionRank(tier: String): Double? {
  val text = tier.trim().lowercase()
  return when {
    text.endsWith("k") -> text.dropLast(1).toDoubleOrNull()
    // «512» и «720p» — это пиксели по стороне, а не тысячи: приводим к тем же единицам.
    text.endsWith("p") -> text.dropLast(1).toDoubleOrNull()?.div(1000)
    else -> text.toDoubleOrNull()?.div(1000)
  }
}

/**
 * Слово «референс» после предлога «до»: там родительный падеж, а не именительный — «до 1 референса»,
 * «до 8 референсов». Формы передаются готовыми, потому что [pluralRu] знает про числа, а не про
 * падежи: подставить туда именительный «референс» и получить «берёт до 1 референс» проще всего.
 */
private fun referenceWord(count: Int) = pluralRu(count, "референса", "референсов", "референсов")

/**
 * Русское склонение числительных. Полное правило, а не «единица только для 1»: 21 — «файл»,
 * 11 — «файлов».
 */
fun pluralRu(count: Int, one: String, few: String, many: String): String = when {
  count % 10 == 1 && count % 100 != 11 -> one
  count % 10 in 2..4 && count % 100 !in 12..14 -> few
  else -> many
}
