package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.ModelCatalog
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.isMetaModel
import com.aiopenpocket.agent.core.llm.OpenRouterCatalogService
import com.aiopenpocket.agent.core.llm.usage.LlmUsageDao
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class SettingsViewModel(
  private val settingsRepository: SettingsRepository,
  private val userPreferencesRepository: UserPreferencesRepository,
  private val modelCatalogLoader: ModelCatalogLoader,
  private val openRouterCatalogService: OpenRouterCatalogService,
  private val themeRepository: ThemeRepository,
  private val llmUsageDao: LlmUsageDao,
) : ViewModel() {

  private val _state = MutableStateFlow(SettingsUiState())
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      // Всё чтение с диска — на IO: и EncryptedSharedPreferences (первое обращение поднимает
      // Keystore), и каталог моделей. Живой каталог OpenRouter — сотни моделей, а не пять строк
      // ассета, так что на главном потоке это заметный кадр при каждом входе в Настройки.
      val loaded = withContext(Dispatchers.IO) {
        LoadedSettings(
          openRouterKey = settingsRepository.getApiKey("OPENROUTER"),
          selectedModel = settingsRepository.getSelectedModel(),
          availableModels = modelOptions(modelCatalogLoader.loadOpenRouter()),
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          goalMaxIterations = userPreferencesRepository.getGoalModeMaxIterations(),
          chatFontScale = userPreferencesRepository.getChatFontScale(),
        )
      }
      _state.update {
        it.copy(
          openRouterKey = loaded.openRouterKey,
          selectedModel = loaded.selectedModel,
          availableModels = loaded.availableModels,
          personaId = loaded.personaId,
          goalMaxIterations = loaded.goalMaxIterations,
          chatFontScale = loaded.chatFontScale,
          themeMode = themeRepository.mode.value,
          personas = PersonaCatalog.all.map { it.id },
        )
      }
      refreshOpenRouterCatalog()
    }
    loadUsage()
  }

  /**
   * Расход за неделю — отдельной корутиной от остальных настроек.
   *
   * Не в общий блок чтения: там всё нужно ДО первого кадра (иначе селекторы моргнут значениями по
   * умолчанию), а суммы по таблице учёта — нет. Их задержка не должна откладывать весь экран, и
   * пустая карточка, доехавшая на кадр позже, честнее пустого экрана.
   */
  private fun loadUsage() {
    viewModelScope.launch {
      val summary = withContext(Dispatchers.IO) {
        runCatching {
          val since = System.currentTimeMillis() - USAGE_WINDOW_MS
          val totals = llmUsageDao.totalsSince(since)
          val background = llmUsageDao.backgroundTotalsSince(since)
          UsageSummary(
            calls = totals.calls,
            promptTokens = totals.promptTokens,
            completionTokens = totals.completionTokens,
            costUsd = totals.costUsd,
            estimatedCalls = totals.estimatedCalls,
            backgroundCalls = background.calls,
            backgroundTokens = background.promptTokens + background.completionTokens,
            byModel = llmUsageDao.totalsByModelSince(since).map {
              UsageSummary.ModelUsage(
                model = it.model,
                calls = it.calls,
                tokens = it.promptTokens + it.completionTokens,
                costUsd = it.costUsd,
              )
            },
          )
        }.getOrNull()
      }
      // Отказ чтения оставляет usage = null, то есть карточку «данных пока нет»: статистика не тот
      // повод, ради которого стоит показывать пользователю ошибку на экране настроек.
      if (summary != null) _state.update { it.copy(usage = summary) }
    }
  }

  /** Снимок того, что читается с диска, — чтобы прочитать всё одним заходом на IO. */
  private data class LoadedSettings(
    val openRouterKey: String,
    val selectedModel: String,
    val availableModels: List<String>,
    val personaId: String,
    val goalMaxIterations: Int,
    val chatFontScale: ChatFontScale,
  )

  private companion object {
    /** Окно сводки: неделя. Достаточно, чтобы увидеть будни и выходные, и коротко для «сейчас». */
    const val USAGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
  }

  /**
   * Тянет свежий список моделей OpenRouter (не чаще раза в сутки — TTL внутри сервиса) и
   * подставляет его в селектор.
   *
   * Сеть недоступна — список остаётся прежним (кеш или сид), ошибку не показываем: пользователь
   * пришёл в Настройки не за отчётом о фоновом запросе.
   */
  private fun refreshOpenRouterCatalog() {
    viewModelScope.launch {
      // Берём каталог, который вернул сервис, вместо повторного чтения файла: это и лишний поход
      // на диск, и расхождение — запись в кеш могла не удаться, и тогда мы показали бы старый
      // список, имея на руках новый.
      val snapshot = openRouterCatalogService.refreshIfStale()
      val options = modelOptions(snapshot.catalog)
      _state.update {
        val model = if (snapshot.authoritative) pruneMissingModel(it.selectedModel, options) else it.selectedModel
        it.copy(availableModels = options, selectedModel = model, saved = it.saved && model == it.selectedModel)
      }
    }
  }

  /**
   * Выбранная модель, которой больше нет в каталоге, сбрасывается на "AUTO".
   *
   * OpenRouter регулярно снимает модели. Без этого Настройки продолжали бы показывать снятую модель
   * как выбранную, `save()` записал бы её обратно, а цепочка провайдеров молча ушла бы на первую по
   * priority — разрыв между тем, что показано, и тем, что происходит.
   *
   * Вызывается ТОЛЬКО на авторитетном каталоге (сеть или кеш). Сид из ассетов — пять курируемых
   * моделей, и по нему нельзя заключить, что выбранная модель снята: иначе первый же запуск без
   * сети молча сбрасывал бы выбор пользователя.
   */
  private fun pruneMissingModel(selected: String, options: List<String>): String =
    if (selected in options) selected else "AUTO"

  fun updateOpenRouterKey(key: String) {
    _state.update { it.copy(openRouterKey = key, saved = false) }
  }

  fun updateModel(model: String) {
    _state.update { it.copy(selectedModel = model, saved = false) }
  }

  /**
   * Опции селектора модели: "AUTO" (идти по priority) + модели каталога.
   *
   * Каталог уже отсортирован по priority (ModelCatalogLoader) — тот же порядок, что и в
   * фоллбэк-цепочке, поэтому список в Настройках совпадает с реальным порядком попыток. Отсев тот же,
   * что в цепочке: только модели Meta с function calling (см. [isMetaModel] и
   * [com.aiopenpocket.agent.core.llm.ModelEntry.supportsTools]) — кеш прошлой версии ещё может держать
   * чужие модели, и показать их значило бы предложить выбор, который цепочка молча отбросит.
   */
  private fun modelOptions(catalog: ModelCatalog): List<String> =
    // distinct: одинаковые имена — краш LazyColumn на дублирующихся ключах. Живой каталог уже
    // дедуплицирован (OpenRouterCatalogService), но ассет правится руками, а кеш мог быть записан
    // прошлой версией приложения.
    (listOf("AUTO") + catalog.models.filter { it.supportsTools && isMetaModel(it.name) }.map { it.name }).distinct()

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

  /**
   * Сохранения не должны наслаиваться. Пока запись шла на главном потоке, два нажатия были
   * сериализованы сами собой; после переезда на IO — уже нет, и два блока могли перемешаться,
   * записав ключ от одного нажатия и модель от другого.
   */
  private val saveMutex = Mutex()

  fun save() {
    val current = _state.value
    viewModelScope.launch {
      saveMutex.withLock { persist(current) }
    }
  }

  private suspend fun persist(current: SettingsUiState) {
    // Запись ключей — тоже IO, как и чтение: EncryptedSharedPreferences шифрует значение на
    // вызывающем потоке, а первое обращение к ним поднимает Keystore. Нажать «Сохранить» можно
    // раньше, чем завершится чтение в init, — и тогда главный поток вставал бы на том же мониторе.
    withContext(Dispatchers.IO) {
      settingsRepository.setApiKey("OPENROUTER", current.openRouterKey)
      settingsRepository.setSelectedModel(current.selectedModel)
    }
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
