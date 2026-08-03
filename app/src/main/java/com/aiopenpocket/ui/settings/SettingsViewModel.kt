package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.llmProviderOf
import com.aiopenpocket.agent.core.llm.ModelCatalog
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.OpenRouterCatalogService
import com.aiopenpocket.agent.core.persona.PersonaCatalog
import com.aiopenpocket.data.repository.SettingsRepository
import com.aiopenpocket.data.repository.ThemeRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.ui.theme.ThemeMode
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SettingsViewModel(
  private val settingsRepository: SettingsRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val modelCatalogLoader: ModelCatalogLoader,
  private val openRouterCatalogService: OpenRouterCatalogService,
  private val themeRepository: ThemeRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(SettingsUiState())
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      // Всё чтение с диска — на IO: и EncryptedSharedPreferences (первое обращение поднимает
      // Keystore), и каталог моделей. Живой каталог OpenRouter — сотни моделей, а не пять строк
      // ассета, так что на главном потоке это заметный кадр при каждом входе в Настройки.
      val loaded = withContext(Dispatchers.IO) {
        val provider = llmProviderOf(settingsRepository.getSelectedProvider())
        LoadedSettings(
          provider = provider,
          nvidiaKey = settingsRepository.getApiKey("NVIDIA"),
          openRouterKey = settingsRepository.getApiKey("OPENROUTER"),
          selectedModel = settingsRepository.getSelectedModel(),
          availableModels = modelOptions(provider),
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          goalMaxIterations = userPreferencesRepository.getGoalModeMaxIterations(),
          chatFontScale = userPreferencesRepository.getChatFontScale(),
        )
      }
      _state.update {
        it.copy(
          nvidiaKey = loaded.nvidiaKey,
          openRouterKey = loaded.openRouterKey,
          provider = loaded.provider,
          selectedModel = loaded.selectedModel,
          availableModels = loaded.availableModels,
          personaId = loaded.personaId,
          goalMaxIterations = loaded.goalMaxIterations,
          chatFontScale = loaded.chatFontScale,
          themeMode = themeRepository.mode.value,
          personas = PersonaCatalog.all.map { it.id },
        )
      }
      refreshOpenRouterCatalog(loaded.provider)
    }
  }

  /** Снимок того, что читается с диска, — чтобы прочитать всё одним заходом на IO. */
  private data class LoadedSettings(
    val provider: LlmProvider,
    val nvidiaKey: String,
    val openRouterKey: String,
    val selectedModel: String,
    val availableModels: List<String>,
    val personaId: String,
    val goalMaxIterations: Int,
    val chatFontScale: ChatFontScale,
  )

  /**
   * Тянет свежий список моделей OpenRouter (не чаще раза в сутки — TTL внутри сервиса) и
   * подставляет его в селектор.
   *
   * Только когда выбран OpenRouter: ходить в сеть за чужим каталогом, пока пользователь сидит на
   * NIM, незачем. Сеть недоступна — список остаётся прежним (кеш или сид), ошибку не показываем:
   * пользователь пришёл в Настройки не за отчётом о фоновом запросе.
   */
  private fun refreshOpenRouterCatalog(provider: LlmProvider) {
    if (provider != LlmProvider.OPENROUTER) return
    viewModelScope.launch {
      // Берём каталог, который вернул сервис, вместо повторного чтения файла: это и лишний поход
      // на диск, и расхождение — запись в кеш могла не удаться, и тогда мы показали бы старый
      // список, имея на руках новый.
      val options = modelOptions(openRouterCatalogService.refreshIfStale())
      _state.update {
        // Провайдер мог смениться, пока мы ходили в сеть — тогда список уже не наш.
        if (it.provider != LlmProvider.OPENROUTER) return@update it
        it.copy(availableModels = options, selectedModel = pruneMissingModel(it.selectedModel, options))
      }
    }
  }

  /**
   * Выбранная модель, которой больше нет в каталоге, сбрасывается на "AUTO".
   *
   * OpenRouter регулярно снимает модели. Без этого Настройки продолжали бы показывать снятую модель
   * как выбранную, `save()` записал бы её обратно, а цепочка провайдеров молча ушла бы на первую по
   * priority — ровно тот разрыв между тем, что показано, и тем, что происходит, ради которого
   * сбрасывается модель при смене провайдера.
   */
  private fun pruneMissingModel(selected: String, options: List<String>): String =
    if (selected in options) selected else "AUTO"

  fun updateNvidiaKey(key: String) {
    _state.update { it.copy(nvidiaKey = key, saved = false) }
  }

  fun updateOpenRouterKey(key: String) {
    _state.update { it.copy(openRouterKey = key, saved = false) }
  }

  /**
   * Смена провайдера сбрасывает выбранную модель на "AUTO": имена моделей у вендоров свои, и
   * оставленное `deepseek-ai/...` на OpenRouter просто не нашлось бы в каталоге — фолбэк молча увёл
   * бы пользователя на первую попавшуюся модель вместо той, что показана в интерфейсе.
   */
  fun updateProvider(provider: LlmProvider) {
    if (provider == _state.value.provider) return
    // Сам переключатель — мгновенно, без похода на диск: это обработчик тапа на главном потоке.
    // Список моделей на это время — только "AUTO": он верен для любого провайдера, а показать
    // остатки списка ПРЕДЫДУЩЕГО вендора было бы ровно тем враньём, ради которого сбрасывается
    // модель.
    _state.update {
      it.copy(provider = provider, selectedModel = "AUTO", availableModels = listOf("AUTO"), saved = false)
    }
    viewModelScope.launch {
      val options = withContext(Dispatchers.IO) { modelOptions(provider) }
      _state.update { if (it.provider == provider) it.copy(availableModels = options) else it }
      refreshOpenRouterCatalog(provider)
    }
  }

  fun updateModel(model: String) {
    _state.update { it.copy(selectedModel = model, saved = false) }
  }

  /**
   * Опции селектора модели: "AUTO" (идти по priority) + модели каталога провайдера.
   *
   * Каталог уже отсортирован по priority (ModelCatalogLoader) — тот же порядок, что и в
   * фоллбэк-цепочке, поэтому список в Настройках совпадает с реальным порядком попыток. Модели без
   * function calling отфильтрованы: см. [com.aiopenpocket.agent.core.llm.ModelEntry.supportsTools].
   */
  private fun modelOptions(provider: LlmProvider): List<String> = modelOptions(
    when (provider) {
      LlmProvider.NIM -> modelCatalogLoader.loadNim()
      LlmProvider.OPENROUTER -> modelCatalogLoader.loadOpenRouter()
    },
  )

  private fun modelOptions(catalog: ModelCatalog): List<String> =
    // distinct: одинаковые имена — краш LazyColumn на дублирующихся ключах. Живой каталог уже
    // дедуплицирован (OpenRouterCatalogService), но ассет правится руками, а кеш мог быть записан
    // прошлой версией приложения.
    (listOf("AUTO") + catalog.models.filter { it.supportsTools }.map { it.name }).distinct()

  fun updatePersona(personaId: String) {
    _state.update { it.copy(personaId = personaId, saved = false) }
  }

  fun updateGoalMaxIterations(value: Int) {
    _state.update { it.copy(goalMaxIterations = value.coerceIn(1, 10), saved = false) }
  }

  fun updateChatFontScale(value: ChatFontScale) {
    _state.update { it.copy(chatFontScale = value, saved = false) }
  }

  /** Тему применяем сразу (реактивно через ThemeRepository), а не по кнопке «Сохранить». */
  fun updateThemeMode(value: ThemeMode) {
    themeRepository.setMode(value)
    _state.update { it.copy(themeMode = value) }
  }

  fun save() {
    val current = _state.value
    settingsRepository.setApiKey("NVIDIA", current.nvidiaKey)
    settingsRepository.setApiKey("OPENROUTER", current.openRouterKey)
    // Провайдер и модель пишем вместе: между ними инвариант (модель принадлежит каталогу
    // провайдера), и запись только одного из них оставила бы настройки в невалидной паре.
    settingsRepository.setSelectedProvider(current.provider.name)
    settingsRepository.setSelectedModel(current.selectedModel)
    viewModelScope.launch {
      // «Сохранено» показываем только после реальной записи в DataStore (suspend), а не до неё.
      userPreferencesRepository.setDefaultPersonaId(current.personaId)
      userPreferencesRepository.setGoalModeMaxIterations(current.goalMaxIterations)
      userPreferencesRepository.setChatFontScale(current.chatFontScale)
      // ...и только если пользователь не успел изменить поля во время записи (иначе соврём «Сохранено»).
      // Сравниваем всё состояние целиком (без флага saved): любое новое поле настройки попадает в
      // проверку автоматически, без ручного дописывания клаузы. Побочный эффект: если ровно в этот
      // момент долетел свежий каталог OpenRouter (availableModels), «Сохранено» не покажется —
      // настройки при этом записаны. Врать «сохранено» на изменившемся состоянии хуже.
      _state.update { s ->
        if (s.copy(saved = false) == current.copy(saved = false)) s.copy(saved = true) else s
      }
    }
  }
}
