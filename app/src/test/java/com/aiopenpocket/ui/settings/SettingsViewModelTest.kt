package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenRouterCatalogCache
import com.aiopenpocket.agent.core.llm.OpenRouterCatalogService
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.data.repository.ThemeRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
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

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    settings = FakeSettingsRepository()
    File(application.filesDir, "openrouter_models.json").delete()
  }

  @After
  fun tearDown() {
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
    return SettingsViewModel(
      settingsRepository = settings,
      userPreferencesRepository = UserPreferencesRepository(testDataStore(application)),
      modelCatalogLoader = loader,
      openRouterCatalogService = OpenRouterCatalogService(cache, loader, engine),
      themeRepository = ThemeRepository(application),
    )
  }

  /**
   * Ждём состояние в РЕАЛЬНОМ времени: `runTest` подменяет время на виртуальное, и обычный
   * `withTimeout` внутри него срабатывает мгновенно, не дав работе на `Dispatchers.IO` (которую
   * планировщик теста не отслеживает) вообще случиться.
   */
  private suspend fun SettingsViewModel.await(predicate: (SettingsUiState) -> Boolean): SettingsUiState =
    withContext(Dispatchers.Default.limitedParallelism(1)) {
      withTimeout(10_000) { state.first(predicate) }
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
    val slow = CompletableDeferred<Unit>()
    val vm = viewModel(gate = slow, openRouterIds = arrayOf("vendor/late"))
    val nimModels = vm.await { it.availableModels.size > 1 }.availableModels

    vm.updateProvider(LlmProvider.OPENROUTER)   // ушёл в сеть и завис на воротах
    vm.updateProvider(LlmProvider.NIM)          // передумал раньше, чем каталог долетел
    vm.await { it.provider == LlmProvider.NIM && it.availableModels.size > 1 }

    slow.complete(Unit)                          // отпускаем протухший ответ

    val state = vm.await { it.provider == LlmProvider.NIM }
    assertEquals(nimModels, state.availableModels)
    assertTrue("каталог OpenRouter не должен был долететь", state.availableModels.none { it == "vendor/late" })
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
}
