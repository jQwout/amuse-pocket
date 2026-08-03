package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackChainTest {

  private fun config(p: LlmProvider, model: String = "model-$p", supportsTools: Boolean = true) = ProviderConfig(
    name = p,
    baseUrl = "https://example/$p",
    model = model,
    supportsTools = supportsTools,
    apiKeyResolver = { "key" },
  )

  private val chain = ProviderFallbackChain(
    mapOf(
      LlmProvider.NIM to listOf(config(LlmProvider.NIM)),
      LlmProvider.OPENROUTER to listOf(config(LlmProvider.OPENROUTER)),
    ),
  )

  @Test
  fun `resolves only the requested provider`() {
    assertEquals(listOf(LlmProvider.NIM), chain.resolve(LlmProvider.NIM).map { it.name })
    assertEquals(listOf(LlmProvider.OPENROUTER), chain.resolve(LlmProvider.OPENROUTER).map { it.name })
  }

  @Test
  fun `preserves model order by priority when nothing is preferred`() {
    val multiModel = ProviderFallbackChain(
      mapOf(
        LlmProvider.NIM to listOf(
          config(LlmProvider.NIM, "model-1"),
          config(LlmProvider.NIM, "model-2"),
          config(LlmProvider.NIM, "model-3"),
        ),
      ),
    )
    assertEquals(listOf("model-1", "model-2", "model-3"), multiModel.resolve(LlmProvider.NIM).map { it.model })
  }

  @Test
  fun `preferred model goes first, the rest stay as fallbacks`() {
    val multiModel = ProviderFallbackChain(
      configs = mapOf(
        LlmProvider.NIM to listOf(
          config(LlmProvider.NIM, "model-1"),
          config(LlmProvider.NIM, "model-2"),
          config(LlmProvider.NIM, "model-3"),
        ),
      ),
      preferredModel = { "model-3" },
    )
    assertEquals(listOf("model-3", "model-1", "model-2"), multiModel.resolve(LlmProvider.NIM).map { it.model })
  }

  /** "AUTO" — сентинел настроек «не выбирал вручную», а не имя модели: порядок остаётся по priority. */
  @Test
  fun `AUTO sentinel is not treated as a model name`() {
    val multiModel = ProviderFallbackChain(
      configs = mapOf(
        LlmProvider.NIM to listOf(config(LlmProvider.NIM, "model-1"), config(LlmProvider.NIM, "model-2")),
      ),
      preferredModel = { "AUTO" },
    )
    assertEquals(listOf("model-1", "model-2"), multiModel.resolve(LlmProvider.NIM).map { it.model })
  }

  /**
   * Регрессия: выбор модели должен работать на ЛЮБОМ провайдере, а не только на NIM. Прежняя
   * проверка `it.name == LlmProvider.NIM` молча возвращала пользователя OpenRouter к дефолтной
   * модели, хотя в Настройках он видел выбранную.
   */
  @Test
  fun `preferred model is honoured on a non-NIM provider`() {
    val openRouter = ProviderFallbackChain(
      configs = mapOf(
        LlmProvider.OPENROUTER to listOf(
          config(LlmProvider.OPENROUTER, "anthropic/claude-sonnet-5"),
          config(LlmProvider.OPENROUTER, "x-ai/grok-4.5"),
        ),
      ),
      preferredModel = { "x-ai/grok-4.5" },
    )
    assertEquals(
      listOf("x-ai/grok-4.5", "anthropic/claude-sonnet-5"),
      openRouter.resolve(LlmProvider.OPENROUTER).map { it.model },
    )
  }

  /** Модель другого вендора в настройках не должна ни утекать в список, ни ломать порядок. */
  @Test
  fun `preferred model from another provider is ignored`() {
    val mixed = ProviderFallbackChain(
      configs = mapOf(
        LlmProvider.NIM to listOf(config(LlmProvider.NIM, "model-1"), config(LlmProvider.NIM, "model-2")),
        LlmProvider.OPENROUTER to listOf(config(LlmProvider.OPENROUTER, "anthropic/claude-sonnet-5")),
      ),
      preferredModel = { "anthropic/claude-sonnet-5" },
    )
    assertEquals(listOf("model-1", "model-2"), mixed.resolve(LlmProvider.NIM).map { it.model })
  }

  /**
   * Каталог OpenRouter — сотни моделей. Без потолка один неверный ключ означал бы сотни попыток
   * подряд на каждый ход: телефон греется, пользователь ждёт, а ответ всё равно будет «не вышло».
   */
  @Test
  fun `the attempt chain is capped`() {
    val huge = ProviderFallbackChain(
      mapOf(LlmProvider.OPENROUTER to (1..300).map { config(LlmProvider.OPENROUTER, "vendor/model-$it") }),
    )
    assertEquals(5, huge.resolve(LlmProvider.OPENROUTER).size)
  }

  /** Потолок не должен отрезать ВЫБРАННУЮ модель, даже если в каталоге она двухсотая. */
  @Test
  fun `a preferred model beyond the cap still goes first`() {
    val huge = ProviderFallbackChain(
      configs = mapOf(LlmProvider.OPENROUTER to (1..300).map { config(LlmProvider.OPENROUTER, "vendor/model-$it") }),
      preferredModel = { "vendor/model-200" },
    )
    val resolved = huge.resolve(LlmProvider.OPENROUTER).map { it.model }
    assertEquals("vendor/model-200", resolved.first())
    assertEquals(5, resolved.size)
  }

  /**
   * Гейт «только модели с function calling» стоит здесь, а не только в сборке конфигов: через
   * resolve проходит каждый способ построить цепочку — живой каталог, правленый руками ассет и кеш,
   * записанный прошлой версией приложения.
   */
  @Test
  fun `models without tool support never enter the chain`() {
    val mixed = ProviderFallbackChain(
      mapOf(
        LlmProvider.NIM to listOf(
          config(LlmProvider.NIM, "no-tools", supportsTools = false),
          config(LlmProvider.NIM, "with-tools"),
        ),
      ),
    )
    assertEquals(listOf("with-tools"), mixed.resolve(LlmProvider.NIM).map { it.model })
  }

  /** Даже если пользователь выбрал такую модель раньше (каталог с тех пор изменился). */
  @Test
  fun `a preferred model without tool support is not resurrected`() {
    val mixed = ProviderFallbackChain(
      configs = mapOf(
        LlmProvider.NIM to listOf(
          config(LlmProvider.NIM, "no-tools", supportsTools = false),
          config(LlmProvider.NIM, "with-tools"),
        ),
      ),
      preferredModel = { "no-tools" },
    )
    assertEquals(listOf("with-tools"), mixed.resolve(LlmProvider.NIM).map { it.model })
  }

  @Test
  fun `missing provider resolves to empty list`() {
    val partial = ProviderFallbackChain(mapOf(LlmProvider.NIM to listOf(config(LlmProvider.NIM))))
    assertTrue(partial.resolve(LlmProvider.OPENROUTER).isEmpty())
  }
}
