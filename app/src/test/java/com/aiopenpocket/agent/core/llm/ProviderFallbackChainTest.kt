package com.aiopenpocket.agent.core.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackChainTest {

  private fun config(model: String, supportsTools: Boolean = true) = ProviderConfig(
    name = LlmProvider.OPENROUTER,
    baseUrl = "https://example/openrouter",
    model = model,
    supportsTools = supportsTools,
    apiKeyResolver = { "key" },
  )

  private fun chainOf(vararg configs: ProviderConfig, preferred: String? = null) = ProviderFallbackChain(
    configs = mapOf(LlmProvider.OPENROUTER to configs.toList()),
    preferredModel = { preferred },
  )

  @Test
  fun `preserves model order by priority when nothing is preferred`() {
    val chain = chainOf(config("model-1"), config("model-2"), config("model-3"))
    assertEquals(listOf("model-1", "model-2", "model-3"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /**
   * Выбор модели в Настройках действует: выбранная идёт первой, остальные — фолбэком. Прежняя
   * проверка `it.name == LlmProvider.NIM` однажды молча возвращала пользователя OpenRouter к
   * дефолтной модели, хотя в Настройках он видел выбранную.
   */
  @Test
  fun `preferred model goes first, the rest stay as fallbacks`() {
    val chain = chainOf(config("meta/muse-spark-1.3"), config("meta/muse-spark-1.2"), config("meta/muse-wonder"), preferred = "meta/muse-wonder")
    assertEquals(
      listOf("meta/muse-wonder", "meta/muse-spark-1.3", "meta/muse-spark-1.2"),
      chain.resolve(LlmProvider.OPENROUTER).map { it.model },
    )
  }

  /** "AUTO" — сентинел настроек «не выбирал вручную», а не имя модели: порядок остаётся по priority. */
  @Test
  fun `AUTO sentinel is not treated as a model name`() {
    val chain = chainOf(config("model-1"), config("model-2"), preferred = "AUTO")
    assertEquals(listOf("model-1", "model-2"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /** Модель, которой нет в каталоге (снята или осталась от прошлой версии), не ломает порядок. */
  @Test
  fun `preferred model missing from the catalog is ignored`() {
    val chain = chainOf(config("model-1"), config("model-2"), preferred = "anthropic/claude-sonnet-5")
    assertEquals(listOf("model-1", "model-2"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /**
   * Без потолка один неверный ключ означал бы попытку на каждую модель каталога подряд на каждый ход:
   * телефон греется, пользователь ждёт, а ответ всё равно будет «не вышло».
   */
  @Test
  fun `the attempt chain is capped`() {
    val huge = chainOf(*(1..300).map { config("vendor/model-$it") }.toTypedArray())
    assertEquals(5, huge.resolve(LlmProvider.OPENROUTER).size)
  }

  /** Потолок не должен отрезать ВЫБРАННУЮ модель, даже если в каталоге она двухсотая. */
  @Test
  fun `a preferred model beyond the cap still goes first`() {
    val huge = chainOf(*(1..300).map { config("vendor/model-$it") }.toTypedArray(), preferred = "vendor/model-200")
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
    val chain = chainOf(config("no-tools", supportsTools = false), config("with-tools"))
    assertEquals(listOf("with-tools"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /** Даже если пользователь выбрал такую модель раньше (каталог с тех пор изменился). */
  @Test
  fun `a preferred model without tool support is not resurrected`() {
    val chain = chainOf(config("no-tools", supportsTools = false), config("with-tools"), preferred = "no-tools")
    assertEquals(listOf("with-tools"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  /**
   * Кеш, записанный версией без фильтра, ещё может держать contributor-модель, а пользователь — иметь
   * её выбранной. В цепочку она не попадает ни так, ни так.
   */
  @Test
  fun `a model that trains on user data never enters the chain, even when preferred`() {
    val chain = chainOf(
      config("meta/muse-spark-1.3-contributor"),
      config("meta/muse-spark-1.3"),
      preferred = "meta/muse-spark-1.3-contributor",
    )
    assertEquals(listOf("meta/muse-spark-1.3"), chain.resolve(LlmProvider.OPENROUTER).map { it.model })
  }

  @Test
  fun `empty catalog resolves to empty list`() {
    assertTrue(chainOf().resolve(LlmProvider.OPENROUTER).isEmpty())
  }
}
