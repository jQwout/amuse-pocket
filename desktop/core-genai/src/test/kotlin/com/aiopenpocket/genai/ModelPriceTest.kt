package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Цена — то место, где ошибка стоит денег, поэтому она разобрана здесь, а не в экране.
 *
 * Все три единицы взяты с живого API 2026-08-06: `seedream-4.5` берёт за кадр, `riverflow-v2.5-pro`
 * — за кадр, но по-разному за тиры, `flux.2-pro` — за мегапиксель, `gemini-3-pro-image` — за токен,
 * `krea-2-large` не публикует цену вовсе.
 */
class ModelPriceTest {

  @Test
  fun `a per-image rate becomes a frame price`() {
    val price = listOf(ImagePrice(unit = "image", costUsd = 0.04)).modelPrice()

    assertEquals(ModelPrice.PerFrame(0.04), price)
    assertEquals("$0.04 за кадр", price.shortLabel())
    assertEquals(0.04, price.frameUsd())
  }

  @Test
  fun `tier rates are kept and the chosen tier wins`() {
    // riverflow-v2.5-pro: базовая цена без тира плюс надбавки за 2K и 4K.
    val price = listOf(
      ImagePrice(unit = "image", costUsd = 0.13),
      ImagePrice(unit = "image", costUsd = 0.15, variant = "2k"),
      ImagePrice(unit = "image", costUsd = 0.17, variant = "4k"),
    ).modelPrice()

    assertEquals(0.13, price.frameUsd())
    assertEquals(0.17, price.frameUsd("4K"), "тир выбран, а цена показана базовая")
    assertEquals("$0.17 за кадр", price.shortLabel("4K"))
    assertEquals("$0.13 · 2K — $0.15 · 4K — $0.17 за кадр", price.fullLabel())
  }

  @Test
  fun `a price list without a base row falls back to the first tier`() {
    // grok-imagine: строки без тира нет вовсе, только 1k и 2k.
    val price = listOf(
      ImagePrice(unit = "image", costUsd = 0.05, variant = "1k"),
      ImagePrice(unit = "image", costUsd = 0.07, variant = "2k"),
    ).modelPrice()

    assertEquals(0.05, price.frameUsd())
    assertEquals(0.07, price.frameUsd("2K"))
  }

  @Test
  fun `a megapixel rate is never comparable to a frame price`() {
    // $0.03 за мегапиксель и $0.03 за кадр — одно число и разный смысл. Пустить их в одну
    // сортировку значит назвать самой дешёвой не ту модель.
    val perMegapixel = listOf(ImagePrice(unit = "megapixel", costUsd = 0.03)).modelPrice()

    assertEquals(ModelPrice.PerMegapixel(0.03), perMegapixel)
    assertNull(perMegapixel.frameUsd(), "ставка за мегапиксель попала в сравнение цен за кадр")
    assertEquals("$0.03 за Мп", perMegapixel.shortLabel())
    assertEquals("$0.03 за мегапиксель", perMegapixel.fullLabel())
  }

  @Test
  fun `a token rate shows no number at all`() {
    // Ставка $0.00012 за токен не говорит ничего, пока неизвестно, сколько токенов стоит кадр,
    // а этого каталог не публикует.
    val price = listOf(ImagePrice(unit = "token", costUsd = 0.00012)).modelPrice()

    assertEquals(ModelPrice.PerToken, price)
    assertNull(price.frameUsd())
    assertEquals("по токенам", price.shortLabel())
  }

  @Test
  fun `no pricing and an unfamiliar unit both read as no price`() {
    assertEquals(ModelPrice.Unknown, emptyList<ImagePrice>().modelPrice())
    // Незнакомая единица — не повод выдумать ей смысл.
    assertEquals(ModelPrice.Unknown, listOf(ImagePrice(unit = "credit", costUsd = 24.0)).modelPrice())
    assertEquals("цены нет", ModelPrice.Unknown.shortLabel())
  }

  @Test
  fun `money keeps its meaningful digits and never stops at one`() {
    assertEquals("$0.03", formatPrice(0.03))
    assertEquals("$0.075", formatPrice(0.075))
    assertEquals("$0.0042", formatPrice(0.0042))
    // «$0.1» рядом с «$0.13» читается как опечатка, а не как цена.
    assertEquals("$0.10", formatPrice(0.1))
    assertEquals("$1.00", formatPrice(1.0))
  }
}
