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
 * Сид-каталог OpenRouter РЕАЛЬНО парсится из ассетов.
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
  fun `seed offers tool-capable models`() {
    assertTrue(loader.loadOpenRouterSeed().models.any { it.supportsTools })
  }

  /** Сид — первый запуск и офлайн: чужая модель в нём была бы выбором, который цепочка молча отбросит. */
  @Test
  fun `seed holds only Meta models`() {
    val foreign = loader.loadOpenRouterSeed().models.map { it.name }.filterNot(::isMetaModel)
    assertEquals(emptyList<String>(), foreign)
  }

  /** Каталог прямого Meta Model API так же молча упал бы на fallback из одной модели при опечатке. */
  @Test
  fun `Meta catalog parses, is sorted and points at the direct API`() {
    val catalog = loader.loadMeta()
    assertTrue("ожидали больше одной модели, похоже на fallback", catalog.models.size > 1)
    assertEquals(ModelCatalog.META_BASE_URL, catalog.baseUrl)
    assertEquals(catalog.models.map { it.priority }.sorted(), catalog.models.map { it.priority })
  }

  /**
   * У прямого API id без префикса вендора — «meta/muse-spark-1.3» это имя на OpenRouter. Каждая модель
   * с function calling, и ни одной contributor-версии: на их данных Meta обучается.
   */
  @Test
  fun `Meta catalog holds direct-API ids with tools and no contributor models`() {
    val models = loader.loadMeta().models
    assertEquals(emptyList<String>(), models.map { it.name }.filter { it.contains('/') })
    assertTrue(models.all { it.supportsTools })
    assertEquals(emptyList<String>(), models.map { it.name }.filter(::trainsOnUserData))
  }

  /** Модель без цены в прайсе — и расход на маршруте Meta молча остаётся без денег. */
  @Test
  fun `every Meta catalog model has a list price`() {
    val unpriced = loader.loadMeta().models.map { it.name }
      .filter { metaListPriceUsd(it, SseUsage(promptTokens = 1)) == null }
    assertEquals(emptyList<String>(), unpriced)
  }
}
