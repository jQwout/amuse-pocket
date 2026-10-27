package com.aiopenpocket.agent.core.persona

import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The persona sheet in Settings shows every persona's tone, perks and sample quotes. The English fields
 * fall back to the Russian ones, so a persona added without English texts would quietly show Russian in
 * the English interface — here that becomes a red test.
 */
class PersonaCatalogTest {

  private val cyrillic = Regex("[А-Яа-яЁё]")

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  @Test
  fun `every persona card text is English in the English interface`() {
    AppLanguage.code = AppLanguage.EN
    for (persona in PersonaCatalog.all) {
      val texts = listOf(persona.displayName, persona.tone) + persona.perks +
        persona.quotes.flatMap { listOf(it.situation, it.line) }
      for (text in texts) {
        assertFalse("${persona.id}: $text", cyrillic.containsMatchIn(text))
      }
    }
  }

  @Test
  fun `the Russian interface keeps the Russian texts`() {
    AppLanguage.code = AppLanguage.RU
    val butler = PersonaCatalog.findById("silent_butler")!!
    assertEquals("сухой, минимальный", butler.tone)
    assertEquals(listOf("Без лишних слов", "Делает молча"), butler.perks)
    assertEquals("«Готово.»", butler.quotes.first().line)
  }
}
