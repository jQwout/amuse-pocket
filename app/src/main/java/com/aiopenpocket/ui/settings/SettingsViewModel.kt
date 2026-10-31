package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.ModelCatalog
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.trainsOnUserData
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
  private val themeRepository: ThemeRepository,
  private val llmUsageDao: LlmUsageDao,
) : ViewModel() {

  private val _state = MutableStateFlow(SettingsUiState())
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      // All disk reads are on IO: both EncryptedSharedPreferences (the first access brings up the Keystore) and
      // the model catalog.
      val loaded = withContext(Dispatchers.IO) {
        val metaModels = modelOptions(modelCatalogLoader.loadMeta())
        LoadedSettings(
          metaKey = settingsRepository.getApiKey("META"),
          // The Meta catalog is static and complete, so it can tell that a model is gone (it was removed
          // from the asset in a new version), and "Auto" can be shown right away.
          selectedMetaModel = pruneMissingModel(settingsRepository.getSelectedModel("META"), metaModels),
          metaModels = metaModels,
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          goalMaxIterations = userPreferencesRepository.getGoalModeMaxIterations(),
          chatFontScale = userPreferencesRepository.getChatFontScale(),
        )
      }
      _state.update {
        it.copy(
          metaKey = loaded.metaKey,
          selectedMetaModel = loaded.selectedMetaModel,
          metaModels = loaded.metaModels,
          personaId = loaded.personaId,
          goalMaxIterations = loaded.goalMaxIterations,
          chatFontScale = loaded.chatFontScale,
          themeMode = themeRepository.mode.value,
          personas = PersonaCatalog.all.map { it.id },
        )
      }
    }
    loadUsage()
  }

  /**
   * Spend over the week — in a coroutine separate from the other settings.
   *
   * Not in the shared read block: everything there is needed BEFORE the first frame (otherwise the selectors
   * would blink with default values), while the sums over the accounting table are not. Their delay must not
   * hold up the whole screen, and an empty card arriving a frame later is more honest than an empty screen.
   */
  private fun loadUsage() {
    viewModelScope.launch {
      val summary = llmUsageDao.weeklySummary()
      // A read failure leaves usage = null, i.e. the "no data yet" card: statistics are not a reason to show the
      // user an error on the settings screen.
      if (summary != null) _state.update { it.copy(usage = summary) }
    }
  }

  /** A snapshot of what is read from disk — so everything is read in one go on IO. */
  private data class LoadedSettings(
    val metaKey: String,
    val selectedMetaModel: String,
    val metaModels: List<String>,
    val personaId: String,
    val goalMaxIterations: Int,
    val chatFontScale: ChatFontScale,
  )

  /**
   * A selected model that is no longer in the catalog is reset to "AUTO".
   *
   * Meta retires models too. Without this Settings would keep showing a retired model as selected,
   * `save()` would write it back, and the provider chain would silently go to the first one by priority —
   * a gap between what is shown and what happens. The catalog is a static asset and the complete list, so
   * it can be trusted to say a model is gone.
   */
  private fun pruneMissingModel(selected: String, options: List<String>): String =
    if (selected in options) selected else "AUTO"

  fun updateMetaKey(key: String) {
    _state.update { it.copy(metaKey = key, saved = false) }
  }

  fun updateModel(model: String) {
    _state.update { it.copy(selectedMetaModel = model, saved = false) }
  }

  /**
   * The model selector's options: "AUTO" (go by priority) + the catalog's models.
   *
   * The catalog is already sorted by priority (ModelCatalogLoader) — the same order as in the fallback
   * chain, so the list in Settings matches the real order of attempts. The filter is the same as in the
   * chain: only models with function calling (see [com.aiopenpocket.agent.core.llm.ModelEntry.supportsTools])
   * and none that train on the user's data.
   */
  private fun modelOptions(catalog: ModelCatalog): List<String> =
    // distinct: identical names crash LazyColumn on duplicate keys, and the asset is edited by hand.
    (
      listOf("AUTO") + catalog.models
        .filter { it.supportsTools && !trainsOnUserData(it.name) }
        .map { it.name }
      ).distinct()

  fun updatePersona(personaId: String) {
    _state.update { it.copy(personaId = personaId, saved = false) }
  }

  fun updateGoalMaxIterations(value: Int) {
    _state.update { it.copy(goalMaxIterations = value.coerceIn(1, 10), saved = false) }
  }

  fun updateChatFontScale(value: ChatFontScale) {
    _state.update { it.copy(chatFontScale = value, saved = false) }
  }

  /**
   * The language is applied right away too: the setting is mirrored into AppLanguage, and the interface
   * redraws in place; the agent's answers switch to this language from the next turn.
   */
  fun updateLanguage(code: String) {
    viewModelScope.launch { userPreferencesRepository.setLanguage(code) }
  }

  /** The theme is applied right away (reactively through ThemeRepository), not by the "Save" button. */
  fun updateThemeMode(value: ThemeMode) {
    themeRepository.setMode(value)
    _state.update { it.copy(themeMode = value) }
  }

  /**
   * Saves must not overlap. While the write ran on the main thread, two taps were serialized by themselves;
   * after the move to IO they no longer are, and two blocks could interleave, writing the key from one tap
   * and the model from the other.
   */
  private val saveMutex = Mutex()

  fun save() {
    val current = _state.value
    viewModelScope.launch {
      saveMutex.withLock { persist(current) }
    }
  }

  private suspend fun persist(current: SettingsUiState) {
    // Writing keys is IO too, like reading: EncryptedSharedPreferences encrypts the value on the calling
    // thread, and the first access to them brings up the Keystore. "Save" can be tapped before the read in
    // init finishes — and then the main thread would stall on the same monitor.
    withContext(Dispatchers.IO) {
      // trim: a key is usually pasted from the clipboard, and a trailing space or newline would go into the
      // Authorization header — from the outside that looks like "the key is right, but Meta answers 401".
      settingsRepository.setApiKey("META", current.metaKey.trim())
      settingsRepository.setSelectedModel(current.selectedMetaModel, "META")
    }
    // "Saved" is shown only after the actual write to DataStore (suspend), not before it.
    userPreferencesRepository.setDefaultPersonaId(current.personaId)
    userPreferencesRepository.setGoalModeMaxIterations(current.goalMaxIterations)
    userPreferencesRepository.setChatFontScale(current.chatFontScale)
    // ...and only if the user did not manage to change the fields during the write (otherwise we would lie
    // "Saved"). The whole state is compared (without the saved flag): any new settings field gets into the
    // check automatically, without adding a clause by hand. Lying "saved" about a changed state is worse
    // than a missing "Saved".
    _state.update { s ->
      if (s.copy(saved = false) == current.copy(saved = false)) s.copy(saved = true) else s
    }
  }
}
