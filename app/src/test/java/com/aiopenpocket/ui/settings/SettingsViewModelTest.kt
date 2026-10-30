package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.agent.core.llm.usage.LlmUsageByModel
import com.aiopenpocket.agent.core.llm.usage.LlmUsageDao
import com.aiopenpocket.agent.core.llm.usage.LlmUsageEntity
import com.aiopenpocket.agent.core.llm.usage.LlmUsageTotals
import com.aiopenpocket.data.repository.ThemeRepository
import kotlinx.coroutines.Dispatchers
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
import com.aiopenpocket.testing.testDataStore

/**
 * [SettingsViewModel] — the key and model choice logic: what is shown in the model list, what goes to
 * storage and when.
 *
 * The tests wait for state (`state.first { ... }` under a timeout), not `advanceUntilIdle`: the ViewModel
 * deliberately goes to `Dispatchers.IO` (prefs bring up the Keystore), and `runTest`'s virtual scheduler
 * does not track real work on IO.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SettingsViewModelTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val application = ApplicationProvider.getApplicationContext<Application>()

  private val loader = ModelCatalogLoader(application)
  private lateinit var settings: FakeSettingsRepository

  /**
   * ViewModels are created through a store so that `@After` calls `onCleared` and cancels `viewModelScope`.
   * Without that the ViewModel's coroutines outlive their own test: they keep writing to prefs after
   * `resetMain()` — that is, exactly the "the wrong test fails, not the guilty one" mechanism for which
   * DataStore is isolated in this same commit.
   */
  private val viewModelStore = ViewModelStore()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    settings = FakeSettingsRepository()
  }

  @After
  fun tearDown() {
    viewModelStore.clear()
    Dispatchers.resetMain()
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

    override fun getSelectedModel(provider: String): String = values["model_$provider"] ?: "AUTO"

    override fun setSelectedModel(model: String, provider: String) {
      writes++
      values["model_$provider"] = model
    }
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

  private fun viewModel(usageDao: LlmUsageDao = emptyUsageDao): SettingsViewModel {
    val vm = SettingsViewModel(
      settingsRepository = settings,
      userPreferencesRepository = UserPreferencesRepository(testDataStore(application)),
      modelCatalogLoader = loader,
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
    val vm = viewModel()
    vm.await { it.availableModels.isNotEmpty() }

    vm.updateMetaKey("meta-test")
    vm.updateModel("muse-wonder-1.0")
    vm.await { it.selectedMetaModel == "muse-wonder-1.0" }

    assertEquals(0, settings.writes)
    assertNull(settings.values["key_META"])
  }

  /**
   * A key from the clipboard is saved without surrounding spaces: a trailing newline in the Authorization
   * header looks like "the key is right, but Meta answers 401".
   */
  @Test
  fun `Сохранить пишет ключ Meta обрезанным и выбранную модель`() = runTest {
    val vm = viewModel()
    vm.await { it.metaModels.contains("muse-wonder-1.0") }

    vm.updateMetaKey("  meta-test\n")
    vm.updateModel("muse-wonder-1.0")
    vm.save()
    // The write went to IO (the Keystore encrypts on the calling thread), so we wait for confirmation:
    // the saved flag is raised only after a real write.
    vm.await { it.saved }

    assertEquals("meta-test", settings.values["key_META"])
    assertEquals("muse-wonder-1.0", settings.values["model_META"])
  }

  /** There is no other key to save: the OpenRouter field and its storage are gone. */
  @Test
  fun `Сохранить не пишет никакого ключа, кроме Meta`() = runTest {
    val vm = viewModel()
    vm.await { it.availableModels.isNotEmpty() }

    vm.updateMetaKey("meta-test")
    vm.save()
    vm.await { it.saved }

    assertEquals(setOf("key_META"), settings.values.keys.filter { it.startsWith("key_") }.toSet())
  }

  /**
   * The picker lists the static Meta catalog: direct-API ids, tools only, no vendor-trained variant.
   */
  @Test
  fun `в выборе модели каталог Meta без contributor-версий`() = runTest {
    val vm = viewModel()
    val state = vm.await { it.metaModels.isNotEmpty() }

    assertEquals("AUTO", state.availableModels.first())
    assertTrue(state.availableModels.contains("muse-spark-1.3"))
    assertTrue(state.availableModels.none { it.contains('/') || it.endsWith("-contributor") })
  }

  /** The status line under the key field reacts to the field as the person types, before "Save". */
  @Test
  fun `индикатор ключа следует за полем ввода`() = runTest {
    val vm = viewModel()
    assertFalse(vm.await { it.metaModels.isNotEmpty() }.hasKey)

    vm.updateMetaKey("meta-test")
    assertTrue(vm.state.value.hasKey)

    vm.updateMetaKey("   ")
    assertFalse("пробелы — это не ключ", vm.state.value.hasKey)
  }

  /** An already saved key is shown in the field on entering Settings. */
  @Test
  fun `сохранённый ключ Meta показывается при входе`() = runTest {
    settings.values["key_META"] = "meta-saved"
    val vm = viewModel()
    val state = vm.await { it.metaKey.isNotEmpty() }

    assertEquals("meta-saved", state.metaKey)
    assertTrue(state.hasKey)
  }

  /**
   * Meta retires models too. Settings must not show a withdrawn model as chosen: `save()` would write it
   * back, and the provider chain would silently go to the first one by priority.
   */
  @Test
  fun `выбранная модель, исчезнувшая из каталога, сбрасывается на AUTO`() = runTest {
    settings.values["model_META"] = "muse-retired-9.9"

    val vm = viewModel()
    val state = vm.await { it.metaModels.isNotEmpty() }

    assertEquals("AUTO", state.selectedMetaModel)
  }

  /** The same model that is in the catalog is not touched. */
  @Test
  fun `существующая модель остаётся выбранной`() = runTest {
    settings.values["model_META"] = "muse-wonder-1.0"

    val vm = viewModel()
    val state = vm.await { it.metaModels.isNotEmpty() }

    assertEquals("muse-wonder-1.0", state.selectedMetaModel)
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
          model = "muse-spark-1.3",
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

    val vm = viewModel(usageDao = dao)
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
    assertEquals("muse-spark-1.3", usage.byModel.single().model)
    assertEquals(42_000L, usage.byModel.single().tokens)
  }
}
