package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.ModelCatalog
import com.aiopenpocket.agent.core.llm.ModelCatalogLoader
import com.aiopenpocket.agent.core.llm.isMetaModel
import com.aiopenpocket.agent.core.llm.trainsOnUserData
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
      // All disk reads are on IO: both EncryptedSharedPreferences (the first access brings up the Keystore) and
      // the model catalog. The live OpenRouter catalog is hundreds of models, not five asset lines, so on the
      // main thread it would be a noticeable frame on every entry into Settings.
      val loaded = withContext(Dispatchers.IO) {
        val metaModels = modelOptions(modelCatalogLoader.loadMeta(), LlmProvider.META)
        LoadedSettings(
          openRouterKey = settingsRepository.getApiKey("OPENROUTER"),
          metaKey = settingsRepository.getApiKey("META"),
          selectedModel = settingsRepository.getSelectedModel("OPENROUTER"),
          // The Meta catalog is static and complete — unlike the OpenRouter seed, it can tell that a model is gone
          // (it was removed from the asset in a new version), and "Auto" can be shown right away.
          selectedMetaModel = pruneMissingModel(settingsRepository.getSelectedModel("META"), metaModels),
          openRouterModels = modelOptions(modelCatalogLoader.loadOpenRouter(), LlmProvider.OPENROUTER),
          metaModels = metaModels,
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          goalMaxIterations = userPreferencesRepository.getGoalModeMaxIterations(),
          chatFontScale = userPreferencesRepository.getChatFontScale(),
        )
      }
      _state.update {
        it.copy(
          openRouterKey = loaded.openRouterKey,
          metaKey = loaded.metaKey,
          selectedModel = loaded.selectedModel,
          selectedMetaModel = loaded.selectedMetaModel,
          openRouterModels = loaded.openRouterModels,
          metaModels = loaded.metaModels,
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
   * Spend over the week — in a coroutine separate from the other settings.
   *
   * Not in the shared read block: everything there is needed BEFORE the first frame (otherwise the selectors
   * would blink with default values), while the sums over the accounting table are not. Their delay must not
   * hold up the whole screen, and an empty card arriving a frame later is more honest than an empty screen.
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
      // A read failure leaves usage = null, i.e. the "no data yet" card: statistics are not a reason to show the
      // user an error on the settings screen.
      if (summary != null) _state.update { it.copy(usage = summary) }
    }
  }

  /** A snapshot of what is read from disk — so everything is read in one go on IO. */
  private data class LoadedSettings(
    val openRouterKey: String,
    val metaKey: String,
    val selectedModel: String,
    val selectedMetaModel: String,
    val openRouterModels: List<String>,
    val metaModels: List<String>,
    val personaId: String,
    val goalMaxIterations: Int,
    val chatFontScale: ChatFontScale,
  )

  private companion object {
    /** The summary window: a week. Enough to see weekdays and weekends, and short enough for "now". */
    const val USAGE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000
  }

  /**
   * Fetches a fresh OpenRouter model list (no more than once a day — the TTL is inside the service) and
   * puts it into the selector.
   *
   * The network is unavailable — the list stays as it was (cache or seed), no error is shown: the user came
   * to Settings not for a report about a background request.
   */
  private fun refreshOpenRouterCatalog() {
    viewModelScope.launch {
      // We take the catalog the service returned instead of reading the file again: that would be both an extra
      // trip to disk and a mismatch — the cache write may have failed, and then we would show the old list
      // while holding the new one.
      val snapshot = openRouterCatalogService.refreshIfStale()
      val options = modelOptions(snapshot.catalog, LlmProvider.OPENROUTER)
      // Only the OpenRouter route's choice is checked against the OpenRouter catalog — with a Meta key entered too:
      // the choice is kept in case the Meta key is removed, and a retired model must not come back to life in it.
      _state.update {
        val model = if (snapshot.authoritative) pruneMissingModel(it.selectedModel, options) else it.selectedModel
        it.copy(openRouterModels = options, selectedModel = model, saved = it.saved && model == it.selectedModel)
      }
    }
  }

  /**
   * A selected model that is no longer in the catalog is reset to "AUTO".
   *
   * OpenRouter regularly retires models. Without this Settings would keep showing a retired model as
   * selected, `save()` would write it back, and the provider chain would silently go to the first one by
   * priority — a gap between what is shown and what happens.
   *
   * Called ONLY on an authoritative catalog: for OpenRouter — network or cache, for Meta — the static asset
   * (there is no live list, the asset is the complete list). The OpenRouter seed is five curated models, and
   * it cannot tell that the selected model was retired: otherwise the very first launch without a network
   * would silently reset the user's choice.
   */
  private fun pruneMissingModel(selected: String, options: List<String>): String =
    if (selected in options) selected else "AUTO"

  fun updateOpenRouterKey(key: String) {
    _state.update { it.copy(openRouterKey = key, saved = false) }
  }

  fun updateMetaKey(key: String) {
    _state.update { it.copy(metaKey = key, saved = false) }
  }

  /** The model is written into the choice of the route shown right now — that is the list the user sees. */
  fun updateModel(model: String) {
    _state.update {
      if (it.provider == LlmProvider.META) {
        it.copy(selectedMetaModel = model, saved = false)
      } else {
        it.copy(selectedModel = model, saved = false)
      }
    }
  }

  /**
   * The model selector's options: "AUTO" (go by priority) + the catalog's models.
   *
   * The catalog is already sorted by priority (ModelCatalogLoader) — the same order as in the fallback
   * chain, so the list in Settings matches the real order of attempts. The filter is the same as in the
   * chain: only Meta models with function calling (see [isMetaModel] and
   * [com.aiopenpocket.agent.core.llm.ModelEntry.supportsTools]) — a previous version's cache may still hold
   * foreign models, and showing them would offer a choice the chain silently drops. The "meta/" prefix
   * filter is for OpenRouter only, as in the chain (LlmModule): Meta ids have no prefix.
   */
  private fun modelOptions(catalog: ModelCatalog, provider: LlmProvider): List<String> =
    // distinct: identical names crash LazyColumn on duplicate keys. The live catalog is already deduplicated
    // (OpenRouterCatalogService), but the asset is edited by hand, and the cache may have been written by a
    // previous app version.
    (
      listOf("AUTO") + catalog.models
        .filter { it.supportsTools && !trainsOnUserData(it.name) }
        .filter { provider != LlmProvider.OPENROUTER || isMetaModel(it.name) }
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
      settingsRepository.setApiKey("OPENROUTER", current.openRouterKey.trim())
      settingsRepository.setApiKey("META", current.metaKey.trim())
      settingsRepository.setSelectedModel(current.selectedModel, "OPENROUTER")
      settingsRepository.setSelectedModel(current.selectedMetaModel, "META")
    }
    // "Saved" is shown only after the actual write to DataStore (suspend), not before it.
    userPreferencesRepository.setDefaultPersonaId(current.personaId)
    userPreferencesRepository.setGoalModeMaxIterations(current.goalMaxIterations)
    userPreferencesRepository.setChatFontScale(current.chatFontScale)
    // ...and only if the user did not manage to change the fields during the write (otherwise we would lie
    // "Saved"). The whole state is compared (without the saved flag): any new settings field gets into the
    // check automatically, without adding a clause by hand. A side effect: if a fresh OpenRouter catalog
    // (openRouterModels) arrives at exactly that moment, "Saved" will not show — the settings are written
    // anyway. Lying "saved" about a changed state is worse.
    _state.update { s ->
      if (s.copy(saved = false) == current.copy(saved = false)) s.copy(saved = true) else s
    }
  }
}
