package com.aiopenpocket.agent.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionsGuideTest {

  @Test fun empty_text_covers_nothing() {
    val c = InstructionsGuide.analyze("")
    assertFalse(c.role); assertFalse(c.format); assertFalse(c.length); assertFalse(c.tone); assertFalse(c.metaInstruction)
  }

  @Test fun russian_text_lights_all_four() {
    val c = InstructionsGuide.analyze(
      "Ты — тренер по бегу. Отвечай списком, не более 5 предложений, в дружелюбном тоне.",
    )
    assertTrue("$c", c.role && c.format && c.length && c.tone)
    assertFalse(c.metaInstruction)
  }

  @Test fun english_text_lights_all_four() {
    val c = InstructionsGuide.analyze(
      "You are a running coach. Reply as a bullet list, under 80 words, in a friendly tone.",
    )
    assertTrue("$c", c.role && c.format && c.length && c.tone)
    assertFalse(c.metaInstruction)
  }

  @Test fun each_item_is_independent() {
    assertTrue(InstructionsGuide.analyze("Your job is to track expenses.").role)
    assertFalse(InstructionsGuide.analyze("Your job is to track expenses.").format)
    assertTrue(InstructionsGuide.analyze("Отвечай кратко").length)
    assertTrue(InstructionsGuide.analyze("Be brief").length)
    assertFalse(InstructionsGuide.analyze("Be brief").tone)
    assertTrue(InstructionsGuide.analyze("Формальный тон").tone)
    assertTrue(InstructionsGuide.analyze("Верни таблицу").format)
    assertTrue(InstructionsGuide.analyze("Return JSON").format)
  }

  @Test fun a_bare_number_is_not_a_length_limit() {
    assertFalse(InstructionsGuide.analyze("Ты помогаешь с 3 проектами").length)
  }

  @Test fun meta_instructions_are_flagged_in_both_languages() {
    assertTrue(InstructionsGuide.analyze("Think step by step before answering").metaInstruction)
    assertTrue(InstructionsGuide.analyze("Думай пошагово").metaInstruction)
    assertTrue(InstructionsGuide.analyze("Ты языковая модель").metaInstruction)
    assertTrue(InstructionsGuide.analyze("Use chain of thought").metaInstruction)
    assertTrue(InstructionsGuide.analyze("Set temperature low").metaInstruction)
    assertFalse(InstructionsGuide.analyze("You are a running coach").metaInstruction)
  }

  @Test fun matching_is_case_insensitive() {
    assertTrue(InstructionsGuide.analyze("YOU ARE A COACH").role)
  }
}
