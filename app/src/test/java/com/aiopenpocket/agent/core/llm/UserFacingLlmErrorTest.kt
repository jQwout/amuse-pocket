package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace english-ui-on-the-demo-path-223241
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [userFacingLlmError] is the text a person actually reads when a turn fails — a red banner in the
 * chat and the push about a failed background turn. Two things went wrong with it on a device
 * (orchestrator/evidence/M3):
 *
 * 1. Every branch was a bare Russian literal, so the English demo path showed «Модель не ответила: нет
 *    сети или сбой у провайдера» to a judge with a bad connection.
 * 2. Three branches named a provider the person was not using, and sent them to the wrong site to check
 *    the wrong key. There is one provider now, the Meta Model API, and a key problem must say so.
 */
class UserFacingLlmErrorTest {

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  /** Every code the function branches on, plus an unknown one for the `else` branch. */
  private val allCodes = listOf(
    "blank_key", "401", "402", "403", "429", "400", "413",
    CODE_LENGTH_EMPTY, CODE_LENGTH_TOOL_CALL, "empty_response", "no_provider", null,
  )

  @Test
  fun `no branch leaves Russian in the English interface`() {
    AppLanguage.code = AppLanguage.EN
    for (code in allCodes) {
      for (interrupted in listOf(true, false)) {
        val text = userFacingLlmError(code, interrupted)
        assertTrue("empty text for code=$code", text.isNotBlank())
        assertFalse(
          "Russian in the English text for code=$code interrupted=$interrupted: $text",
          CYRILLIC.containsMatchIn(text),
        )
      }
    }
  }

  /** The Russian side still exists — a half-done move to [com.aiopenpocket.i18n.tr] that answers in English both times is also a bug. */
  @Test
  fun `every branch speaks Russian in the Russian interface`() {
    AppLanguage.code = AppLanguage.RU
    for (code in allCodes) {
      val text = userFacingLlmError(code, interrupted = false)
      assertTrue("no Russian for code=$code: $text", CYRILLIC.containsMatchIn(text))
    }
  }

  @Test
  fun `a rejected key or an empty balance names the Meta Model API and nothing else`() {
    for (language in listOf(AppLanguage.RU, AppLanguage.EN)) {
      AppLanguage.code = language
      for (code in listOf("401", "403", "402")) {
        val text = userFacingLlmError(code, interrupted = false)
        assertFalse("$language/$code names a provider that is gone: $text", text.contains("OpenRouter"))
        assertTrue("$language/$code does not say whose key it is: $text", text.contains("Meta Model API"))
      }
    }
  }

  /**
   * The missing-key text is the "add your key" message the person has to be able to act on: it says what
   * to add (a Meta key) and where (Settings), in both languages — and it is not a provider's raw 401.
   */
  @Test
  fun `a blank key is the add-your-key message, not an HTTP error`() {
    AppLanguage.code = AppLanguage.EN
    val text = userFacingLlmError("blank_key", interrupted = false)
    assertEquals(missingKeyMessage(), text)
    assertTrue(text, text.contains("Add your Meta API key"))
    assertTrue(text, text.contains("Settings"))
    assertFalse(text, text.contains("401") || text.contains("HTTP"))
    AppLanguage.code = AppLanguage.RU
    val ru = userFacingLlmError("blank_key", interrupted = false)
    assertTrue(ru, ru.contains("Meta API") && ru.contains("Настройках"))
  }

  /** The exact wording of the line the device showed in English, so a silent revert is caught. */
  @Test
  fun `the no-answer line is English on the English path`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals(
      "No answer from the model: either there is no network, or the provider is down. Try again.",
      userFacingLlmError(null, interrupted = false),
    )
  }

  /** A truncation is about the model's token budget, not a key: the key must not leak into those two texts. */
  @Test
  fun `a truncated answer says nothing about any provider`() {
    AppLanguage.code = AppLanguage.EN
    for (code in listOf(CODE_LENGTH_EMPTY, CODE_LENGTH_TOOL_CALL)) {
      val text = userFacingLlmError(code, interrupted = false)
      assertFalse("code=$code names a provider: $text", text.contains("Meta"))
      assertTrue("code=$code does not offer a way out: $text", text.contains("Retry"))
    }
  }

  private companion object {
    val CYRILLIC = Regex("[\\u0400-\\u04FF]")
  }
}
