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
 * The OpenRouter seed catalog REALLY parses from the assets.
 *
 * By design the loader fails silently: a typo in the JSON — and `load()` returns a one-model fallback,
 * having logged a warning nobody reads. From the outside it looks like "for some reason Settings has one
 * model", not like a broken file. Here that silence becomes a red test.
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
   * On OpenRouter a model id is always `vendor/model` — without the prefix the request goes to a 404, and
   * the fallback along the chain silently hides it behind the next model.
   */
  @Test
  fun `OpenRouter model ids are vendor-prefixed`() {
    val bad = loader.loadOpenRouterSeed().models.map { it.name }.filterNot { it.contains('/') }
    assertEquals(emptyList<String>(), bad)
  }

  /** A catalog without a single tools model would leave the agent without tools — that is not configuration, it is a bug. */
  @Test
  fun `seed offers tool-capable models`() {
    assertTrue(loader.loadOpenRouterSeed().models.any { it.supportsTools })
  }

  /** The seed is the first launch and offline: a foreign model in it would be a choice the chain silently drops. */
  @Test
  fun `seed holds only Meta models`() {
    val foreign = loader.loadOpenRouterSeed().models.map { it.name }.filterNot(::isMetaModel)
    assertEquals(emptyList<String>(), foreign)
  }

  /** The direct Meta Model API catalog would fall back to a one-model fallback just as silently on a typo. */
  @Test
  fun `Meta catalog parses, is sorted and points at the direct API`() {
    val catalog = loader.loadMeta()
    assertTrue("ожидали больше одной модели, похоже на fallback", catalog.models.size > 1)
    assertEquals(ModelCatalog.META_BASE_URL, catalog.baseUrl)
    assertEquals(catalog.models.map { it.priority }.sorted(), catalog.models.map { it.priority })
  }

  /**
   * On the direct API ids have no vendor prefix — "meta/muse-spark-1.3" is the name on OpenRouter. Every
   * model has function calling, and there is not a single contributor version: Meta trains on their data.
   */
  @Test
  fun `Meta catalog holds direct-API ids with tools and no contributor models`() {
    val models = loader.loadMeta().models
    assertEquals(emptyList<String>(), models.map { it.name }.filter { it.contains('/') })
    assertTrue(models.all { it.supportsTools })
    assertEquals(emptyList<String>(), models.map { it.name }.filter(::trainsOnUserData))
  }

  /** A model without a price in the price list — and spending on the Meta route silently stays without money. */
  @Test
  fun `every Meta catalog model has a list price`() {
    val unpriced = loader.loadMeta().models.map { it.name }
      .filter { metaListPriceUsd(it, SseUsage(promptTokens = 1)) == null }
    assertEquals(emptyList<String>(), unpriced)
  }
}
