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
 * 2. Three branches named OpenRouter, while there are two routes to the models (see [LlmProvider]):
 *    someone with a Meta Model API key was sent to the wrong site to check the wrong key.
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
      for (provider in listOf(LlmProvider.META, LlmProvider.OPENROUTER, null)) {
        for (interrupted in listOf(true, false)) {
          val text = userFacingLlmError(code, interrupted, provider)
          assertTrue("empty text for code=$code provider=$provider", text.isNotBlank())
          assertFalse(
            "Russian in the English text for code=$code provider=$provider interrupted=$interrupted: $text",
            CYRILLIC.containsMatchIn(text),
          )
        }
      }
    }
  }

  /** The Russian side still exists — a half-done move to [com.aiopenpocket.i18n.tr] that answers in English both times is also a bug. */
  @Test
  fun `every branch speaks Russian in the Russian interface`() {
    AppLanguage.code = AppLanguage.RU
    for (code in allCodes) {
      val text = userFacingLlmError(code, interrupted = false, provider = LlmProvider.META)
      assertTrue("no Russian for code=$code: $text", CYRILLIC.containsMatchIn(text))
    }
  }

  @Test
  fun `a key problem on the Meta route never names OpenRouter`() {
    for (language in listOf(AppLanguage.RU, AppLanguage.EN)) {
      AppLanguage.code = language
      for (code in listOf("blank_key", "401", "403", "402")) {
        val text = userFacingLlmError(code, interrupted = false, provider = LlmProvider.META)
        assertFalse("$language/$code sends a Meta key owner to OpenRouter: $text", text.contains("OpenRouter"))
        assertTrue("$language/$code does not say whose key it is: $text", text.contains("Meta Model API"))
      }
    }
  }

  @Test
  fun `a key problem on the OpenRouter route names OpenRouter`() {
    for (language in listOf(AppLanguage.RU, AppLanguage.EN)) {
      AppLanguage.code = language
      for (code in listOf("blank_key", "401", "403", "402")) {
        val text = userFacingLlmError(code, interrupted = false, provider = LlmProvider.OPENROUTER)
        assertTrue("$language/$code does not say whose key it is: $text", text.contains("OpenRouter"))
        assertFalse("$language/$code blames Meta for an OpenRouter key: $text", text.contains("Meta"))
      }
    }
  }

  /** An unknown route (an empty chain, an error from inside the stream): the wording must fit both. */
  @Test
  fun `without a known route no provider is named at all`() {
    for (language in listOf(AppLanguage.RU, AppLanguage.EN)) {
      AppLanguage.code = language
      for (code in listOf("blank_key", "401", "403", "402")) {
        val text = userFacingLlmError(code, interrupted = false, provider = null)
        assertFalse("$language/$code guesses a provider: $text", text.contains("OpenRouter"))
        assertFalse("$language/$code guesses a provider: $text", text.contains("Meta"))
      }
    }
  }

  /** The same failure must read differently on the two routes — otherwise the route is being ignored. */
  @Test
  fun `the route changes the message`() {
    AppLanguage.code = AppLanguage.EN
    for (code in listOf("blank_key", "401", "402")) {
      val meta = userFacingLlmError(code, interrupted = false, provider = LlmProvider.META)
      val openRouter = userFacingLlmError(code, interrupted = false, provider = LlmProvider.OPENROUTER)
      assertFalse("code=$code reads the same on both routes: $meta", meta == openRouter)
    }
  }

  /** The exact wording of the line the device showed in English, so a silent revert is caught. */
  @Test
  fun `the no-answer line is English on the English path`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals(
      "No answer from the model: either there is no network, or the provider is down. Try again.",
      userFacingLlmError(null, interrupted = false, provider = LlmProvider.META),
    )
  }

  /** A truncation is about the model's token budget, not a key: the route must not leak into those two texts. */
  @Test
  fun `a truncated answer says nothing about any provider`() {
    AppLanguage.code = AppLanguage.EN
    for (code in listOf(CODE_LENGTH_EMPTY, CODE_LENGTH_TOOL_CALL)) {
      val text = userFacingLlmError(code, interrupted = false, provider = LlmProvider.OPENROUTER)
      assertFalse("code=$code names a provider: $text", text.contains("OpenRouter"))
      assertTrue("code=$code does not offer a way out: $text", text.contains("Retry"))
    }
  }

  private companion object {
    val CYRILLIC = Regex("[\\u0400-\\u04FF]")
  }
}
