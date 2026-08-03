package com.aiopenpocket.agent.core.llm

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Каталоги провайдеров РЕАЛЬНО парсятся из ассетов.
 *
 * Загрузчик по устройству отказывает молча: опечатка в JSON — и `load()` возвращает fallback из
 * одной модели, залогировав warning, который никто не читает. Со стороны это выглядит как «в
 * Настройках почему-то одна модель», а не как сломанный файл. Здесь эта тишина становится красным
 * тестом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ModelCatalogLoaderTest {

  private val application = RuntimeEnvironment.getApplication() as Application
  private val loader = ModelCatalogLoader(application, OpenRouterCatalogCache(application))

  @Test
  fun `NIM catalog parses and is sorted by priority`() {
    val catalog = loader.loadNim()
    assertTrue("ожидали больше одной модели, похоже на fallback", catalog.models.size > 1)
    assertEquals(ModelCatalog.DEFAULT_BASE_URL, catalog.baseUrl)
    assertEquals(catalog.models.map { it.priority }.sorted(), catalog.models.map { it.priority })
  }

  @Test
  fun `OpenRouter seed parses and is sorted by priority`() {
    val catalog = loader.loadOpenRouterSeed()
    assertTrue("ожидали больше одной модели, похоже на fallback", catalog.models.size > 1)
    assertEquals(ModelCatalog.OPENROUTER_BASE_URL, catalog.baseUrl)
    assertEquals(catalog.models.map { it.priority }.sorted(), catalog.models.map { it.priority })
  }

  /**
   * У OpenRouter id модели всегда `вендор/модель` — без префикса запрос уходит в 404, а фолбэк
   * по цепочке молча спрячет это за следующей моделью.
   */
  @Test
  fun `OpenRouter model ids are vendor-prefixed`() {
    val bad = loader.loadOpenRouterSeed().models.map { it.name }.filterNot { it.contains('/') }
    assertEquals(emptyList<String>(), bad)
  }

  /** Каталог без единой tools-модели оставил бы агента без инструментов — это не конфигурация, это баг. */
  @Test
  fun `both catalogs offer tool-capable models`() {
    assertTrue(loader.loadNim().models.any { it.supportsTools })
    assertTrue(loader.loadOpenRouterSeed().models.any { it.supportsTools })
  }
}
