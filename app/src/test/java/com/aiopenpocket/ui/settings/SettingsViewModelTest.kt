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
import kotlinx.coroutines.delay
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
 * [SettingsViewModel] — логика выбора провайдера: что показывается в списке моделей, что уходит в
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

    override fun getSelectedModel(): String = values["model"] ?: "AUTO"

    override fun setSelectedModel(model: String) {
      writes++
      values["model"] = model
    }

    override fun getSelectedProvider(): String = values["provider"] ?: SettingsRepository.DEFAULT_PROVIDER

    override fun setSelectedProvider(provider: String) {
      writes++
      values["provider"] = provider
    }
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
   * Расход в этих тестах не проверяется — они про ключи, провайдера и каталог моделей. Пустые
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

  /** Ждём, пока каталог реально ляжет в кеш: сервис пишет его ДО того, как обновит состояние. */
  private suspend fun awaitCached(model: String) = withContext(Dispatchers.Default) {
    withTimeout(10_000) {
      while (cache.read()?.catalog?.models?.none { it.name == model } != false) delay(20)
    }
  }

  @Test
  fun `смена провайдера меняет список моделей и сбрасывает выбранную`() = runTest {
    settings.values["model"] = "deepseek-ai/deepseek-v4-flash"
    val vm = viewModel(openRouterIds = arrayOf("vendor/one", "vendor/two"))
    val nimModels = vm.await { it.availableModels.size > 1 }.availableModels

    vm.updateProvider(LlmProvider.OPENROUTER)
    val after = vm.await { it.provider == LlmProvider.OPENROUTER && it.availableModels.contains("vendor/one") }

    assertEquals("AUTO", after.selectedModel)
    assertEquals(listOf("AUTO", "vendor/one", "vendor/two"), after.availableModels)
    // Ни одной модели прежнего вендора: имена у них свои, и оставленная строка не нашлась бы в
    // каталоге — фолбэк молча увёл бы на первую попавшуюся модель.
    assertTrue(after.availableModels.none { it in nimModels.drop(1) })
  }

  @Test
  fun `возврат к NIM возвращает его каталог`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("vendor/one"))
    val nimModels = vm.await { it.availableModels.size > 1 }.availableModels

    vm.updateProvider(LlmProvider.OPENROUTER)
    vm.await { it.availableModels.contains("vendor/one") }
    vm.updateProvider(LlmProvider.NIM)

    assertEquals(nimModels, vm.await { it.provider == LlmProvider.NIM && it.availableModels.size > 1 }.availableModels)
  }

  /** Конвенция экрана: до «Сохранить» в хранилище не уходит ничего. */
  @Test
  fun `до нажатия Сохранить в хранилище ничего не пишется`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("vendor/one"))
    vm.await { it.availableModels.isNotEmpty() }

    vm.updateProvider(LlmProvider.OPENROUTER)
    vm.updateOpenRouterKey("sk-test")
    vm.updateModel("vendor/one")
    vm.await { it.provider == LlmProvider.OPENROUTER }

    assertEquals(0, settings.writes)
    assertNull(settings.values["provider"])
  }

  /** Провайдер и модель — пара: запись одного без другого оставила бы настройки невалидными. */
  @Test
  fun `Сохранить пишет провайдера и модель вместе`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("vendor/one"))
    vm.await { it.availableModels.isNotEmpty() }

    vm.updateProvider(LlmProvider.OPENROUTER)
    vm.await { it.provider == LlmProvider.OPENROUTER && it.availableModels.contains("vendor/one") }
    vm.updateModel("vendor/one")
    vm.updateOpenRouterKey("sk-test")
    vm.save()
    // Запись ушла на IO (Keystore шифрует на вызывающем потоке), поэтому ждём подтверждения:
    // флаг saved взводится только после реальной записи.
    vm.await { it.saved }

    assertEquals("OPENROUTER", settings.values["provider"])
    assertEquals("vendor/one", settings.values["model"])
    assertEquals("sk-test", settings.values["key_OPENROUTER"])
  }

  /**
   * Обновление каталога «в полёте» не должно подменить список, если пользователь за это время ушёл
   * на другого вендора: он увидел бы модели OpenRouter, сидя на NIM.
   */
  @Test
  fun `каталог, долетевший после ухода на другого вендора, игнорируется`() = runTest {
    // Стартуем УЖЕ на OpenRouter: тогда обновление уходит в сеть прямо из init и виснет на воротах,
    // то есть заведомо оказывается «в полёте» к моменту переключения.
    settings.values["provider"] = "OPENROUTER"
    val slow = CompletableDeferred<Unit>()
    val vm = viewModel(gate = slow, openRouterIds = arrayOf("vendor/late"))
    vm.await { it.provider == LlmProvider.OPENROUTER }

    vm.updateProvider(LlmProvider.NIM)          // передумал раньше, чем каталог долетел
    val nimModels = vm.await { it.provider == LlmProvider.NIM && it.availableModels.size > 1 }.availableModels

    slow.complete(Unit)                          // отпускаем протухший ответ
    // Ключевой момент: сначала ДОКАЗЫВАЕМ, что протухший ответ дошёл до конца обработки (кеш
    // пишется прямо перед обновлением состояния), и только потом проверяем отсутствие. Без этого
    // тест проходил и со СНЯТЫМ гейтом — просто успевая проверить состояние раньше ответа.
    awaitCached("vendor/late")

    val leaked = runCatching {
      vm.await(timeoutMs = 2_000) { it.availableModels.contains("vendor/late") }
    }
    assertTrue(
      "каталог OpenRouter не должен был долететь",
      leaked.exceptionOrNull() is TimeoutCancellationException,
    )
    assertEquals(nimModels, vm.state.value.availableModels)
  }

  /**
   * Пока грузится каталог нового вендора, в списке не должно быть моделей ПРЕДЫДУЩЕГО: чтение
   * с диска ушло на IO, и наивная реализация оставила бы старый список висеть на экране.
   */
  @Test
  fun `на время загрузки список моделей сжимается до AUTO, а не показывает чужого вендора`() = runTest {
    val vm = viewModel(openRouterIds = arrayOf("vendor/one"))
    vm.await { it.availableModels.size > 1 }

    vm.updateProvider(LlmProvider.OPENROUTER)

    // Читаем состояние синхронно: `updateProvider` публикует "AUTO" ДО ухода на IO, на том же
    // потоке. Если сжатия списка нет, тест падает при любом раскладе гонки — видно будет либо
    // список NIM, либо список OpenRouter, но не ["AUTO"].
    assertEquals(listOf("AUTO"), vm.state.value.availableModels)
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
    settings.values["provider"] = "OPENROUTER"
    settings.values["model"] = "vendor/chosen"
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
    assertEquals("vendor/chosen", vm.state.value.selectedModel)
  }

  /**
   * OpenRouter регулярно снимает модели. Настройки не должны показывать снятую модель выбранной:
   * `save()` записал бы её обратно, а цепочка провайдеров молча ушла бы на первую по priority.
   */
  @Test
  fun `выбранная модель, исчезнувшая из каталога, сбрасывается на AUTO`() = runTest {
    settings.values["provider"] = "OPENROUTER"
    settings.values["model"] = "vendor/retired"

    val vm = viewModel(openRouterIds = arrayOf("vendor/alive"))
    val state = vm.await { it.availableModels.contains("vendor/alive") }

    assertEquals("AUTO", state.selectedModel)
  }

  /** Та же модель, которая в каталоге есть, обновлением не трогается. */
  @Test
  fun `существующая модель переживает обновление каталога`() = runTest {
    settings.values["provider"] = "OPENROUTER"
    settings.values["model"] = "vendor/alive"

    val vm = viewModel(openRouterIds = arrayOf("vendor/alive", "vendor/other"))
    val state = vm.await { it.availableModels.contains("vendor/other") }

    assertEquals("vendor/alive", state.selectedModel)
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
          model = "vendor/alive",
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
    assertEquals("vendor/alive", usage.byModel.single().model)
    assertEquals(42_000L, usage.byModel.single().tokens)
  }
}
