package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.llmProviderOf
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
      val provider = llmProviderOf(settingsRepository.getSelectedProvider())
      _state.update {
        it.copy(
          nvidiaKey = settingsRepository.getApiKey("NVIDIA"),
          openRouterKey = settingsRepository.getApiKey("OPENROUTER"),
          provider = provider,
          selectedModel = settingsRepository.getSelectedModel(),
          availableModels = modelOptions(provider),
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          goalMaxIterations = userPreferencesRepository.getGoalModeMaxIterations(),
          chatFontScale = userPreferencesRepository.getChatFontScale(),
          themeMode = themeRepository.mode.value,
          personas = PersonaCatalog.all.map { it.id },
        )
      }
      refreshOpenRouterCatalog(provider)
    }
  }

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
      openRouterCatalogService.refreshIfStale()
      _state.update {
        // Провайдер мог смениться, пока мы ходили в сеть — тогда список уже не наш.
        if (it.provider == LlmProvider.OPENROUTER) it.copy(availableModels = modelOptions(it.provider)) else it
      }
    }
  }

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
    _state.update {
      it.copy(
        provider = provider,
        selectedModel = "AUTO",
        availableModels = modelOptions(provider),
        saved = false,
      )
    }
    refreshOpenRouterCatalog(provider)
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
  private fun modelOptions(provider: LlmProvider): List<String> {
    val catalog = when (provider) {
      LlmProvider.NIM -> modelCatalogLoader.loadNim()
      LlmProvider.OPENROUTER -> modelCatalogLoader.loadOpenRouter()
    }
    return listOf("AUTO") + catalog.models.filter { it.supportsTools }.map { it.name }
  }

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
