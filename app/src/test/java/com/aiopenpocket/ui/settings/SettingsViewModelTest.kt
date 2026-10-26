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
 * [SettingsViewModel] — логика выбора модели: что показывается в списке моделей, что уходит в
 * хранилище и когда.
 *
 * Тесты ждут состояние (`state.first { ... }` под таймаутом), а не `advanceUntilIdle`: ViewModel
 * сознательно уходит на `Dispatchers.IO` (prefs поднимают Keystore, каталог OpenRouter — сотни
 * моделей), а реальную работу на IO виртуальный планировщик `runTest` не отслеживает.
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
   * ViewModel'и создаются через store, чтобы `@After` вызвал `onCleared` и погасил `viewModelScope`.
   * Без этого корутины ViewModel переживают собственный тест: продолжают писать в prefs и в кеш
   * каталога уже после `resetMain()` — то есть ровно тот механизм «падает не тот тест, который
   * виноват», ради которого в этом же коммите изолирован DataStore.
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

  /** In-memory замена [SettingsRepository]: настоящая поднимает Android Keystore, которого в JVM нет. */
  private class FakeSettingsRepository : SettingsRepository {
    val values = mutableMapOf<String, String>()

    /** Сколько раз что-либо ЗАПИСЫВАЛИ — «сохраняется только по кнопке» проверяется этим. */
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

    /** Выбор OpenRouter — под "model", как до второго маршрута: на нём стоят проверки ниже. */
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
   * Расход в этих тестах не проверяется — они про ключ и каталог моделей. Пустые
   * сводки вместо Room: поднимать базу ради четырёх нулей значило бы привязать тесты настроек к
   * схеме БД, которая до релиза пересоздаётся разрушительно.
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
    // Кладём в store, чтобы его clear() в @After позвал onCleared и отменил viewModelScope.
    // Ключ свой на каждый экземпляр: под общим ключом второй ViewModel в одном тесте не попал бы
    // в store вовсе — и его viewModelScope пережил бы тест.
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
   * Ждём состояние в РЕАЛЬНОМ времени: `runTest` подменяет время на виртуальное, и обычный
   * `withTimeout` внутри него срабатывает мгновенно, не дав работе на `Dispatchers.IO` (которую
   * планировщик теста не отслеживает) вообще случиться.
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
    // Запись ушла на IO (Keystore шифрует на вызывающем потоке), поэтому ждём подтверждения:
    // флаг saved взводится только после реальной записи.
    vm.await { it.saved }

    assertEquals("meta/one", settings.values["model"])
    assertEquals("sk-test", settings.values["key_OPENROUTER"])
  }

  /**
   * Приложение работает на Muse: модели других вендоров из живого каталога в выбор не попадают —
   * цепочка их всё равно отбросит, и показать их значило бы предложить выбор, который не сработает.
   */
  @Test
  fun `в выборе только модели Meta`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("meta/one", "anthropic/claude-sonnet-5"))
    val state = vm.await { it.availableModels.contains("meta/one") }

    assertFalse(state.availableModels.contains("anthropic/claude-sonnet-5"))
  }

  /**
   * Ключ Meta переводит запросы на прямой Meta Model API — и список моделей вместе с ними: иначе в
   * Настройках выбирались бы id OpenRouter, которых цепочка Meta не знает.
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
   * У маршрутов свой выбор модели: модель, выбранная при ключе Meta, не затирает выбор OpenRouter —
   * он понадобится, если ключ Meta уберут. Ключ из буфера сохраняется без пробелов по краям.
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
   * Нет сети и нет кеша — каталог падает на сид из ассетов, и по нему НЕЛЬЗЯ судить, что модель
   * снята с обслуживания. Иначе первый же запуск в офлайне молча сбрасывал бы выбор пользователя.
   *
   * Проверять здесь нечего «положительного»: на офлайн-пути список ПОСЛЕ обновления совпадает со
   * списком ДО него (оба — сид). Поэтому сначала ждём доказательства, что обновление реально
   * состоялось (движок дошёл до запроса), и только потом проверяем, что сброса не случилось.
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
   * OpenRouter регулярно снимает модели. Настройки не должны показывать снятую модель выбранной:
   * `save()` записал бы её обратно, а цепочка провайдеров молча ушла бы на первую по priority.
   */
  @Test
  fun `выбранная модель, исчезнувшая из каталога, сбрасывается на AUTO`() = runTest {
    settings.values["model"] = "meta/retired"

    val vm = viewModel(openRouterIds = arrayOf("meta/alive"))
    val state = vm.await { it.availableModels.contains("meta/alive") }

    assertEquals("AUTO", state.selectedModel)
  }

  /** Та же модель, которая в каталоге есть, обновлением не трогается. */
  @Test
  fun `существующая модель переживает обновление каталога`() = runTest {
    settings.values["model"] = "meta/alive"

    val vm = viewModel(openRouterIds = arrayOf("meta/alive", "meta/other"))
    val state = vm.await { it.availableModels.contains("meta/other") }

    assertEquals("meta/alive", state.selectedModel)
  }

  /**
   * Сводка расхода доезжает до экрана в том же виде, в каком её посчитала база.
   *
   * Проверяются именно ЧИСЛА, а не факт «что-то загрузилось»: по ним считаются тарифы, и
   * перепутанные местами вход и выход (вход дороже выхода в разы у одних моделей и наоборот у
   * других) дали бы правдоподобную, но неверную цену — ошибку, которую нечем заметить глазами.
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
    // Фон — подмножество общего счёта, и показывается отдельной строкой: пользователь этих вызовов
    // не заказывал, и именно их доля отвечает на «почему расход растёт, когда я ничего не делаю».
    assertEquals(4, usage.backgroundCalls)
    assertEquals(8_200L, usage.backgroundTokens)
    assertEquals("meta/alive", usage.byModel.single().model)
    assertEquals(42_000L, usage.byModel.single().tokens)
  }
}
