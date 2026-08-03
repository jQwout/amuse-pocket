package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [llmProviderOf] — единственная точка чтения провайдера из настроек (строка в prefs).
 *
 * Тихий дефолт здесь не украшение: значение читается на КАЖДОМ ходе, в том числе в фоне из
 * WorkManager, и исключение из-за строки в настройках убивало бы ход агента целиком.
 */
class LlmProviderTest {

  @Test
  fun `known names resolve to their provider`() {
    assertEquals(LlmProvider.NIM, llmProviderOf("NIM"))
    assertEquals(LlmProvider.OPENROUTER, llmProviderOf("OPENROUTER"))
  }

  @Test
  fun `unknown, empty and stale names fall back to NIM`() {
    assertEquals(LlmProvider.NIM, llmProviderOf(""))
    assertEquals(LlmProvider.NIM, llmProviderOf("openrouter"))
    // Значения удалённых констант: у кого-то они остались в prefs с прошлой сборки.
    assertEquals(LlmProvider.NIM, llmProviderOf("AUTO"))
    assertEquals(LlmProvider.NIM, llmProviderOf("GROQ"))
  }

  /** Круговой инвариант: всё, что мы записали в настройки, читается обратно тем же провайдером. */
  @Test
  fun `every provider survives a name round-trip`() {
    LlmProvider.entries.forEach { assertEquals(it, llmProviderOf(it.name)) }
  }
}
