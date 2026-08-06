package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

/**
 * Пропорция кадра числом: `16:9` → 1.777…, `2.39:1` → 2.39.
 *
 * null — строку разобрать не вышло. Это не паранойя: список соотношений приходит от провайдера, там
 * встречается `auto`, и завтра может появиться что угодно ещё. Неразобранное значение показывается
 * как есть, но без картинки-пропорции и без размера — врать пропорцией нельзя.
 */
fun aspectRatioValue(ratio: String): Double? {
  val parts = ratio.split(':')
  if (parts.size != 2) return null
  val w = parts[0].trim().toDoubleOrNull() ?: return null
  val h = parts[1].trim().toDoubleOrNull() ?: return null
  if (w <= 0 || h <= 0) return null
  return w / h
}

/**
 * Тир разрешения в пикселях по длинной стороне: `1K` → 1024, `2K` → 2048, `512` → 512.
 *
 * null — тир незнакомый, и подставлять за провайдера число нельзя.
 */
fun resolutionLongSide(tier: String): Int? {
  val normalized = tier.trim().uppercase()
  normalized.toIntOrNull()?.let { return it.takeIf { px -> px > 0 } }
  val k = normalized.removeSuffix("K").toIntOrNull() ?: return null
  return (k * 1024).takeIf { normalized.endsWith("K") && k > 0 }
}

/**
 * Во что развернётся выбранная пара «тир + пропорция»: `2K` и `2.39:1` → 2048 × 856.
 *
 * **Это оценка, а не обещание провайдера.** Тир задаётся строкой (`1K`, `2K`), а во сколько пикселей
 * он превращается и по какой стороне отмеряется, OpenRouter в каталоге не пишет: в документации
 * `size` умеет и тир, и явные пиксели вроде `2048x2048`, откуда и читается «2K = 2048 по длинной
 * стороне». Модель вправе округлить до кратности восьми или своей сетки. Поэтому UI показывает это
 * число со знаком приблизительности — как ориентир «какой примерно кадр получится», а не как
 * гарантию.
 *
 * null, если хотя бы одна из двух частей не разобралась: половина ответа хуже, чем его отсутствие.
 */
fun estimatedPixels(tier: String?, ratio: String?): Pair<Int, Int>? {
  val long = tier?.let(::resolutionLongSide) ?: return null
  val value = ratio?.let(::aspectRatioValue) ?: return null
  return if (value >= 1.0) {
    long to (long / value).toInt()
  } else {
    (long * value).toInt() to long
  }
}

/** «1.8 Mn» — мегапикселей в кадре. Подпись рядом с размером, как в макете. */
fun megapixels(size: Pair<Int, Int>): String {
  val mp = size.first.toDouble() * size.second / 1_000_000
  return "%.1f".format(java.util.Locale.ROOT, mp) + " Mn"
}
