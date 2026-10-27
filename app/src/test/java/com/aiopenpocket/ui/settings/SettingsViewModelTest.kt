package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenRouterCatalogCache
import com.aiopenpocket.agent.core.llm.OpenRouterCatalogService
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.agent.core.llm.usage.LlmUsageByModel
import com.aiopenpocket.agent.core.llm.usage.LlmUsageDao
import com.aiopenpocket.agent.core.llm.usage.LlmUsageEntity
import com.aiopenpocket.agent.core.llm.usage.LlmUsageTotals
import com.aiopenpocket.data.repository.ThemeRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import com.aiopenpocket.testing.testDataStore

/**
 * [SettingsViewModel] — the model choice logic: what is shown in the model list, what goes to storage
 * and when.
 *
 * The tests wait for state (`state.first { ... }` under a timeout), not `advanceUntilIdle`: the ViewModel
 * deliberately goes to `Dispatchers.IO` (prefs bring up the Keystore, the OpenRouter catalog has hundreds
 * of models), and `runTest`'s virtual scheduler does not track real work on IO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val application = ApplicationProvider.getApplicationContext<Application>()

  private val cache = OpenRouterCatalogCache(application)
  private val loader = ModelCatalogLoader(application, cache)
  private lateinit var settings: FakeSettingsRepository

  /**
   * ViewModels are created through a store so that `@After` calls `onCleared` and cancels `viewModelScope`.
   * Without that the ViewModel's coroutines outlive their own test: they keep writing to prefs and to the
   * catalog cache after `resetMain()` — that is, exactly the "the wrong test fails, not the guilty one"
   * mechanism for which DataStore is isolated in this same commit.
   */
  private val viewModelStore = ViewModelStore()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    settings = FakeSettingsRepository()
    File(application.filesDir, "openrouter_models.json").delete()
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    Dispatchers.resetMain()
    File(application.filesDir, "openrouter_models.json").delete()
  }

  /** An in-memory replacement for [SettingsRepository]: the real one brings up the Android Keystore, which the JVM does not have. */
  private class FakeSettingsRepository : SettingsRepository {
    val values = mutableMapOf<String, String>()

    /** How many times anything was WRITTEN — "saved only on the button" is checked with this. */
    var writes = 0
      private set

    override fun getApiKey(provider: String): String = values["key_$provider"].orEmpty()

    override fun setApiKey(provider: String, key: String) {
      writes++
      values["key_$provider"] = key
    }

    override fun getSelectedModel(provider: String): String = values[modelKey(provider)] ?: "AUTO"

    override fun setSelectedModel(model: String, provider: String) {
      writes++
      values[modelKey(provider)] = model
    }

    /** The OpenRouter choice is under "model", as before the second route: the checks below rely on it. */
    private fun modelKey(provider: String) = if (provider == "OPENROUTER") "model" else "model_$provider"
  }

  private fun catalogJson(vararg ids: String): String {
    val entries = ids.joinToString(",") {
      """{"id":"$it","created":1,"architecture":{"input_modalities":["text"]},
         "top_provider":{"max_completion_tokens":8000},"supported_parameters":["tools"]}"""
    }
    return """{"data":[$entries]}"""
  }

  private fun viewModel(
    gate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
    vararg openRouterIds: String,
  ): SettingsViewModel {
    val engine = MockEngine {
      gate.await()
      respond(
        content = catalogJson(*openRouterIds),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    return viewModelWith(engine)
  }

  private var viewModelSeq = 0

  /**
   * Spending is not checked in these tests — they are about the key and the model catalog. Empty
   * summaries instead of Room: bringing up the database for four zeros would tie the settings tests to a
   * database schema that is recreated destructively until the release.
   */
  private val emptyTotals =
    LlmUsageTotals(calls = 0, promptTokens = 0, completionTokens = 0, costUsd = null, estimatedCalls = 0)

  private val emptyUsageDao = object : LlmUsageDao {
    override suspend fun insert(entity: LlmUsageEntity) = Unit
    override suspend fun totalsSince(since: Long) = emptyTotals
    override suspend fun totalsByModelSince(since: Long) = emptyList<LlmUsageByModel>()
    override suspend fun backgroundTotalsSince(since: Long) = emptyTotals
  }

  private fun viewModelWith(
    engine: MockEngine,
    usageDao: LlmUsageDao = emptyUsageDao,
  ): SettingsViewModel {
    val vm = SettingsViewModel(
      settingsRepository = settings,
      userPreferencesRepository = UserPreferencesRepository(testDataStore(application)),
      modelCatalogLoader = loader,
      openRouterCatalogService = OpenRouterCatalogService(cache, loader, engine),
      themeRepository = ThemeRepository(application),
      llmUsageDao = usageDao,
    )
    // Put into the store so that its clear() in @After calls onCleared and cancels viewModelScope.
    // Each instance gets its own key: under a shared key the second ViewModel in one test would not get
    // into the store at all — and its viewModelScope would outlive the test.
    ViewModelProvider(
      viewModelStore,
      object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
      },
    )["vm-${viewModelSeq++}", SettingsViewModel::class.java]
    return vm
  }

  /**
   * We wait for state in REAL time: `runTest` replaces time with virtual time, and an ordinary
   * `withTimeout` inside it fires instantly, without letting the work on `Dispatchers.IO` (which the test
   * scheduler does not track) happen at all.
   */
  private suspend fun SettingsViewModel.await(
    timeoutMs: Long = 10_000,
    predicate: (SettingsUiState) -> Boolean,
  ): SettingsUiState =
    withContext(Dispatchers.Default) {
      withTimeout(timeoutMs) { state.first(predicate) }
    }

  @Test
  fun `до нажатия Сохранить в хранилище ничего не пишется`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("meta/one"))
    vm.await { it.availableModels.isNotEmpty() }

    vm.updateOpenRouterKey("sk-test")
    vm.updateModel("meta/one")
    vm.await { it.selectedModel == "meta/one" }

    assertEquals(0, settings.writes)
    assertNull(settings.values["key_OPENROUTER"])
  }

  @Test
  fun `Сохранить пишет ключ и модель`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("meta/one"))
    vm.await { it.availableModels.contains("meta/one") }
    vm.updateModel("meta/one")
    vm.updateOpenRouterKey("sk-test")
    vm.save()
    // The write went to IO (the Keystore encrypts on the calling thread), so we wait for confirmation:
    // the saved flag is raised only after a real write.
    vm.await { it.saved }

    assertEquals("meta/one", settings.values["model"])
    assertEquals("sk-test", settings.values["key_OPENROUTER"])
  }

  /**
   * The app works on Muse: models of other vendors from the live catalog do not get into the picker —
   * the chain would drop them anyway, and showing them would offer a choice that does not work.
   */
  @Test
  fun `в выборе только модели Meta`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("meta/one", "anthropic/claude-sonnet-5"))
    val state = vm.await { it.availableModels.contains("meta/one") }

    assertFalse(state.availableModels.contains("anthropic/claude-sonnet-5"))
  }

  /**
   * The Meta key moves requests to the direct Meta Model API — and the model list along with them:
   * otherwise Settings would offer OpenRouter ids that the Meta chain does not know.
   */
  @Test
  fun `с ключом Meta в выборе модели каталога Meta`() = runTest {
    settings.values["key_META"] = "meta-test"
    val vm = viewModel(openRouterIds = arrayOf("meta/one"))
    val state = vm.await { it.metaModels.isNotEmpty() && it.metaKey.isNotEmpty() }

    assertEquals(LlmProvider.META, state.provider)
    assertTrue(state.availableModels.contains("muse-spark-1.3"))
    assertFalse(state.availableModels.contains("meta/one"))
  }

  /**
   * Each route has its own model choice: a model chosen with the Meta key does not overwrite the OpenRouter
   * choice — it will be needed if the Meta key is removed. A key from the clipboard is saved without
   * surrounding spaces.
   */
  @Test
  fun `выбор модели Meta сохраняется отдельно от OpenRouter`() = runTest {
    settings.values["model"] = "meta/one"
    val vm = viewModel(openRouterIds = arrayOf("meta/one"))
    vm.await { it.openRouterModels.contains("meta/one") }

    vm.updateMetaKey("  meta-test\n")
    vm.updateModel("muse-wonder-1.0")
    vm.save()
    vm.await { it.saved }

    assertEquals("meta-test", settings.values["key_META"])
    assertEquals("muse-wonder-1.0", settings.values["model_META"])
    assertEquals("meta/one", settings.values["model"])
  }

  /**
   * No network and no cache — the catalog falls back to the seed from the assets, and it CANNOT tell that
   * a model was withdrawn. Otherwise the very first offline launch would silently reset the user's choice.
   *
   * There is nothing "positive" to check here: on the offline path the list AFTER the refresh matches the
   * list BEFORE it (both are the seed). So we first wait for proof that the refresh really happened (the
   * engine reached the request), and only then check that no reset took place.
   */
  @Test
  fun `сид из ассетов не сбрасывает выбранную модель`() = runTest {
    settings.values["model"] = "meta/chosen"
    val attempted = CompletableDeferred<Unit>()
    val vm = viewModelWith(
      MockEngine {
        attempted.complete(Unit)
        throw java.io.IOException("нет сети")
      },
    )

    vm.await { it.availableModels.size > 1 }
    withContext(Dispatchers.Default) { withTimeout(10_000) { attempted.await() } }

    val reset = runCatching { vm.await(timeoutMs = 2_000) { it.selectedModel == "AUTO" } }
    assertTrue(
      "сид — не повод сбрасывать выбор пользователя",
      reset.exceptionOrNull() is TimeoutCancellationException,
    )
    assertEquals("meta/chosen", vm.state.value.selectedModel)
  }

  /**
   * OpenRouter regularly withdraws models. Settings must not show a withdrawn model as chosen: `save()`
   * would write it back, and the provider chain would silently go to the first one by priority.
   */
  @Test
  fun `выбранная модель, исчезнувшая из каталога, сбрасывается на AUTO`() = runTest {
    settings.values["model"] = "meta/retired"

    val vm = viewModel(openRouterIds = arrayOf("meta/alive"))
    val state = vm.await { it.availableModels.contains("meta/alive") }

    assertEquals("AUTO", state.selectedModel)
  }

  /** The same model that is in the catalog is not touched by the refresh. */
  @Test
  fun `существующая модель переживает обновление каталога`() = runTest {
    settings.values["model"] = "meta/alive"

    val vm = viewModel(openRouterIds = arrayOf("meta/alive", "meta/other"))
    val state = vm.await { it.availableModels.contains("meta/other") }

    assertEquals("meta/alive", state.selectedModel)
  }

  /**
   * The spending summary reaches the screen in the same form in which the database computed it.
   *
   * What is checked is the NUMBERS, not the fact that "something loaded": pricing is calculated from them,
   * and input and output swapped (input is several times pricier than output for some models and the other
   * way round for others) would give a plausible but wrong price — an error there is no way to spot by eye.
   */
  @Test
  fun `недельная сводка расхода доезжает в состояние экрана`() = runTest {
    val dao = object : LlmUsageDao {
      override suspend fun insert(entity: LlmUsageEntity) = Unit
      override suspend fun totalsSince(since: Long) = LlmUsageTotals(
        calls = 12,
        promptTokens = 40_000,
        completionTokens = 2_000,
        costUsd = 0.031,
        estimatedCalls = 3,
      )
      override suspend fun totalsByModelSince(since: Long) = listOf(
        LlmUsageByModel(
          model = "meta/alive",
          calls = 12,
          promptTokens = 40_000,
          completionTokens = 2_000,
          costUsd = 0.031,
        ),
      )
      override suspend fun backgroundTotalsSince(since: Long) = LlmUsageTotals(
        calls = 4,
        promptTokens = 8_000,
        completionTokens = 200,
        costUsd = 0.004,
        estimatedCalls = 0,
      )
    }

    val vm = viewModelWith(MockEngine { respond("{}") }, usageDao = dao)
    val usage = vm.await { it.usage != null }.usage!!

    assertEquals(12, usage.calls)
    assertEquals(40_000L, usage.promptTokens)
    assertEquals(2_000L, usage.completionTokens)
    assertEquals(42_000L, usage.totalTokens)
    assertEquals(3, usage.estimatedCalls)
    // Background is a subset of the total and is shown on a separate line: the user did not order these
    // calls, and it is their share that answers "why does spending grow when I do nothing".
    assertEquals(4, usage.backgroundCalls)
    assertEquals(8_200L, usage.backgroundTokens)
    assertEquals("meta/alive", usage.byModel.single().model)
    assertEquals(42_000L, usage.byModel.single().tokens)
  }
}
