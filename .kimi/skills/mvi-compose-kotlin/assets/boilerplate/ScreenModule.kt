package com.example.app.feature

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

// ============================================================================
// TODO: Rename "Screen" to your feature name (e.g., "Home", "Profile", "Settings")
// ============================================================================

// ----------------------------------------------------------------------------
// 1. STATE
// ----------------------------------------------------------------------------

@Serializable
data class ScreenState(
    val items: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedId: String? = null
)

// ----------------------------------------------------------------------------
// 2. ACTIONS
// ----------------------------------------------------------------------------

sealed class ScreenAction {
    data object Load : ScreenAction()
    data class ItemClicked(val id: String) : ScreenAction()
    data object Refresh : ScreenAction()
    data object NavBack : ScreenAction()
}

typealias SendAction = (ScreenAction) -> Unit

// ----------------------------------------------------------------------------
// 3. STORE
// ----------------------------------------------------------------------------

class ScreenStore(
    private val scope: CoroutineScope,
    private val storage: ScreenStateStorage
) {
    val appState = MutableStateFlow(ScreenState())
    private val _nav = Channel<String?>(capacity = Channel.UNLIMITED)
    val nav = _nav.receiveAsFlow()

    init {
        scope.launch {
            appState.collect { state ->
                storage.save(state)
            }
        }
    }

    fun handle(action: ScreenAction) {
        when (action) {
            is ScreenAction.Load -> scope.launch {
                appState.update { it.copy(isLoading = true, error = null) }
                try {
                    // TODO: replace with real data source
                    val data = listOf("Item 1", "Item 2", "Item 3")
                    appState.update { it.copy(items = data, isLoading = false) }
                } catch (e: Exception) {
                    appState.update { it.copy(error = e.message, isLoading = false) }
                }
            }

            is ScreenAction.ItemClicked -> {
                appState.update { it.copy(selectedId = action.id) }
                _nav.trySend("detail") // TODO: use your route constant
            }

            is ScreenAction.Refresh -> {
                handle(ScreenAction.Load)
            }

            is ScreenAction.NavBack -> {
                _nav.trySend(null)
            }
        }
    }
}

// ----------------------------------------------------------------------------
// 4. VIEWMODEL
// ----------------------------------------------------------------------------

class ScreenViewModel(
    storage: ScreenStateStorage
) : ViewModel() {
    private val store = ScreenStore(viewModelScope, storage)

    val state: StateFlow<ScreenState> = store.appState.asStateFlow()
    val nav = store.nav

    fun send(action: ScreenAction) = store.handle(action)
}

// ----------------------------------------------------------------------------
// 5. FACTORY
// ----------------------------------------------------------------------------

class ScreenViewModelFactory(
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val storage = ScreenStateStorage(context)
        return ScreenViewModel(storage) as T
    }
}

// ----------------------------------------------------------------------------
// 6. SCREEN (Composable)
// ----------------------------------------------------------------------------

@Composable
fun Screen(
    state: ScreenState,
    sendAction: SendAction
) {
    // TODO: implement your UI
    // state.items — data
    // state.isLoading — loading indicator
    // state.error — error message
    // sendAction(ScreenAction.ItemClicked("id")) — send action
}

// ----------------------------------------------------------------------------
// 7. PERSISTENCE
// ----------------------------------------------------------------------------

class ScreenStateStorage(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    fun save(state: ScreenState) {
        context.openFileOutput("screen_state.json", Context.MODE_PRIVATE).use {
            it.write(json.encodeToString(state).toByteArray())
        }
    }

    fun load(): ScreenState? {
        return try {
            context.openFileInput("screen_state.json").bufferedReader().use {
                json.decodeFromString(it.readText())
            }
        } catch (e: java.io.FileNotFoundException) {
            null
        }
    }
}
