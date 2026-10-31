package com.aiopenpocket.ui.settings

// @trace app-settings-beb470
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.core.identity.ChatFontScale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
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
  private val themeRepository: ThemeRepository,
  private val llmUsageDao: LlmUsageDao,
) : ViewModel() {

  private val _state = MutableStateFlow(SettingsUiState())
  val state: StateFlow<SettingsUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      // All disk reads are on IO: EncryptedSharedPreferences brings up the Keystore on the first access.
      val loaded = withContext(Dispatchers.IO) {
        LoadedSettings(
          metaKey = settingsRepository.getApiKey("META"),
          personaId = userPreferencesRepository.getDefaultPersonaId(),
          chatFontScale = userPreferencesRepository.getChatFontScale(),
        )
      }
      _state.update {
        it.copy(
          metaKey = loaded.metaKey,
          personaId = loaded.personaId,
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
    val personaId: String,
    val chatFontScale: ChatFontScale,
  )

  fun updateMetaKey(key: String) {
    _state.update { it.copy(metaKey = key, saved = false) }
  }

  fun updatePersona(personaId: String) {
    _state.update { it.copy(personaId = personaId, saved = false) }
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
   * after the move to IO they no longer are, and two blocks could interleave and write the key twice.
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
    }
    // "Saved" is shown only after the actual write to DataStore (suspend), not before it.
    userPreferencesRepository.setDefaultPersonaId(current.personaId)
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
