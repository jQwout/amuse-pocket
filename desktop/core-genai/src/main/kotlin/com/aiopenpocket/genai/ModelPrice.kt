package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import java.util.Locale

/**
 * Цена модели, приведённая к тому, что о ней можно честно сказать вслух.
 *
 * Тип нужен ровно затем, чтобы «не знаем» нельзя было случайно нарисовать числом. Прайс приходит в
 * трёх несводимых единицах (см. [ImagePrice]), и разница между ними — это разница между «кадр
 * обойдётся в $0.04» и «сколько выйдет, узнаем после запуска». Держать всё это в `Double?` значит
 * рано или поздно показать ставку за мегапиксель как цену кадра — числа-то похожие.
 *
 * Отдельный тип ещё и потому, что по цене сортируют: сравнивать между собой можно только [PerFrame],
 * и [frameUsd] возвращает `null` для всех остальных не по забывчивости, а чтобы они уехали в конец
 * списка, а не притворились самыми дешёвыми.
 */
sealed interface ModelPrice {

  /**
   * Цена за кадр — готовая, её и показываем. [usd] — базовая, [tiers] непусто у тех, кто берёт за
   * разные тиры разное (`riverflow-v2.5-pro`: $0.13 / $0.15 за 2K / $0.17 за 4K).
   */
  data class PerFrame(val usd: Double, val tiers: Map<String, Double> = emptyMap()) : ModelPrice

  /**
   * Ставка за мегапиксель (`flux.2-pro` — $0.03). В цену кадра НЕ переводится: «1K» — это тир, а
   * не размер, и сколько выйдет мегапикселей, знает только провайдер.
   */
  data class PerMegapixel(val usd: Double) : ModelPrice

  /**
   * Считают по токенам (`gemini-3-pro-image`, `gpt-image-2`). Числа здесь нет намеренно: ставка
   * $0.00012 за токен ничего не говорит, пока неизвестно, сколько токенов стоит кадр, — а этого
   * каталог не публикует. Ровно тот случай, о котором предупреждает `costEstimate`.
   */
  data object PerToken : ModelPrice

  /** Провайдер цену не назвал вовсе (`pricing: []` — так отвечает, например, `krea`). */
  data object Unknown : ModelPrice
}

/**
 * Разбирает строки прайса в то, что можно показать.
 *
 * Единица берётся у первой строки: смешанных ответов (мегапиксели и кадры разом у одного
 * провайдера) живой API не отдаёт, а если отдаст, показать надо одно число, а не два разных смысла
 * в одной ячейке.
 */
fun List<ImagePrice>.modelPrice(): ModelPrice {
  val first = firstOrNull() ?: return ModelPrice.Unknown
  return when (first.unit.lowercase()) {
    UNIT_IMAGE -> {
      val tiers = filter { it.variant.isNotBlank() }.associate { it.variant.uppercase() to it.costUsd }
      // Базовая — строка без тира. Её может не быть вовсе (`grok`: только 1k и 2k), тогда базовой
      // считаем первую: она и уйдёт в запрос, если пользователь тир не выбирал.
      val base = firstOrNull { it.variant.isBlank() }?.costUsd ?: first.costUsd
      ModelPrice.PerFrame(base, tiers)
    }
    UNIT_MEGAPIXEL -> ModelPrice.PerMegapixel(first.costUsd)
    UNIT_TOKEN -> ModelPrice.PerToken
    // Единица, которой мы не знаем, — это не повод выдумать её смысл. Для UI она «цены нет».
    else -> ModelPrice.Unknown
  }
}

/**
 * Число для сортировки «Дешевле» и для сметы: во что обойдётся кадр при выбранном тире.
 *
 * `null` у всех, кроме [ModelPrice.PerFrame], — и это главное свойство функции. Ставку за
 * мегапиксель нельзя ставить в один ряд с ценой кадра: $0.03 за мегапиксель и $0.03 за кадр — числа
 * одинаковые, а смысл разный, и «самой дешёвой» оказалась бы не та модель.
 */
fun ModelPrice.frameUsd(tier: String? = null): Double? = when (this) {
  is ModelPrice.PerFrame -> tier?.let { tiers[it.uppercase()] } ?: usd
  else -> null
}

/** Подпись в строке списка — коротко, одной строкой. */
fun ModelPrice.shortLabel(tier: String? = null): String = when (this) {
  is ModelPrice.PerFrame -> formatPrice(frameUsd(tier) ?: usd) + " за кадр"
  is ModelPrice.PerMegapixel -> formatPrice(usd) + " за Мп"
  ModelPrice.PerToken -> "по токенам"
  ModelPrice.Unknown -> "цены нет"
}

/**
 * Подпись в панели деталей — со всеми тирами и без сокращений.
 *
 * Строка без тира печатается голым числом, а не как «1K — $0.13»: к какому тиру относится базовая
 * цена, провайдер не говорит, и подписать её самой ходовой было бы догадкой в том самом месте, где
 * пользователь смотрит на деньги.
 */
fun ModelPrice.fullLabel(): String = when (this) {
  is ModelPrice.PerFrame ->
    if (tiers.isEmpty()) {
      formatPrice(usd) + " за кадр"
    } else {
      (listOf(formatPrice(usd)) + tiers.map { (tier, cost) -> "$tier — ${formatPrice(cost)}" })
        .joinToString(" · ") + " за кадр"
    }
  is ModelPrice.PerMegapixel -> formatPrice(usd) + " за мегапиксель"
  ModelPrice.PerToken -> "провайдер считает по токенам — цену кадра заранее не назвать"
  ModelPrice.Unknown -> "провайдер цену не публикует"
}

/**
 * Деньги без хвоста нулей: `$0.03`, `$0.075`, `$0.0042`.
 *
 * Отдельно от `formatUsd`, а не вместо него: там четыре знака ВСЕГДА, потому что там складывают
 * потраченное и «$0.0042» обязано быть видно. Здесь же числа из прайса — $0.03, $0.13, — и
 * «$0.0300» в списке из сорока строк читается как другая цена, а не как та же самая.
 */
fun formatPrice(usd: Double): String {
  val text = "%.4f".format(Locale.ROOT, usd).trimEnd('0')
  val decimals = text.length - text.indexOf('.') - 1
  // Минимум два знака: «$0.1» рядом с «$0.13» выглядит опечаткой, а не ценой.
  return "$" + text + "0".repeat((MIN_DECIMALS - decimals).coerceAtLeast(0))
}

private const val UNIT_IMAGE = "image"
private const val UNIT_MEGAPIXEL = "megapixel"
private const val UNIT_TOKEN = "token"
private const val MIN_DECIMALS = 2
