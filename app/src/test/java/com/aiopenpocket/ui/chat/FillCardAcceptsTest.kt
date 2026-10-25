package com.aiopenpocket.ui.chat

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проверка ответа fill-шага. Раньше засчитывалось только точное слово из `answer`, и верный по смыслу
 * ответ («деталей» вместо «конкретики») запирал ученика в уроке: у fill нет кнопки «Дальше».
 */
// Robolectric — ради реального org.json в тестах парсинга карточки (на голом JVM он заглушка).
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FillCardAcceptsTest {

  private fun card(answer: String, vararg answers: String, caseSensitive: Boolean = false) =
    DrivenUiCard.FillCard(template = "В запросе нет ___", answer = answer, caseSensitive = caseSensitive, answers = answers.toList())

  @Test
  fun `канонический ответ засчитывается без учёта регистра и пробелов по краям`() {
    assertTrue(card("конкретики").accepts("  Конкретики "))
  }

  @Test
  fun `засчитывается любой вариант из answers`() {
    val fill = card("конкретики", "деталей", "конкретика")
    assertTrue(fill.accepts("деталей"))
    assertTrue(fill.accepts("КОНКРЕТИКА"))
  }

  @Test
  fun `чужое слово не засчитывается`() {
    assertFalse(card("конкретики", "деталей").accepts("смысла"))
  }

  @Test
  fun `пустой ввод не засчитывается даже при пустой строке в answers`() {
    val fill = card("выход", "")
    assertFalse(fill.accepts(""))
    assertFalse(fill.accepts("   "))
  }

  @Test
  fun `ё и е не различаются без caseSensitive`() {
    assertTrue(card("всё").accepts("все"))
    assertTrue(card("все").accepts("всё"))
  }

  @Test
  fun `повторные пробелы внутри ответа не мешают`() {
    assertTrue(card("контекстное окно").accepts("контекстное   окно"))
  }

  @Test
  fun `caseSensitive требует точный регистр и для вариантов`() {
    val fill = card("API", "Api", caseSensitive = true)
    assertTrue(fill.accepts("API"))
    assertTrue(fill.accepts("Api"))
    assertFalse(fill.accepts("api"))
  }

  @Test
  fun `карточка парсит answers и отбрасывает пустые строки`() {
    val spec = """{"template":"fill","params":{"template":"Токены на ___","answer":"выход","answers":["выходе","",  "  "],"case_sensitive":false}}"""
    val parsed = DrivenUiEngine.parseJsonString(spec)
    assertTrue(parsed is DrivenUiCard.FillCard)
    parsed as DrivenUiCard.FillCard
    assertEquals(listOf("выходе"), parsed.answers)
    assertTrue(parsed.accepts("Выходе"))
  }

  @Test
  fun `карточка без answers парсится как раньше`() {
    val spec = """{"template":"fill","params":{"template":"Токены на ___","answer":"выход"}}"""
    val parsed = DrivenUiEngine.parseJsonString(spec) as DrivenUiCard.FillCard
    assertEquals(emptyList<String>(), parsed.answers)
    assertTrue(parsed.accepts("выход"))
  }
}
