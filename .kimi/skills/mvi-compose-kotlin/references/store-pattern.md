# Store Pattern — Detailed Guide

## Store Responsibilities

1. **Hold state** — `val appState = MutableStateFlow(AppState())`
2. **Dispatch actions** — `fun handle(action: AppAction)`
3. **Emit side effects** — navigation, notifications via `Channel`
4. **Persist state** — auto-save on each emission
5. **Orchestrate business logic** — async operations via `viewModelScope`

## Constructor

Store receives its dependencies as constructor parameters. No framework injection:

```kotlin
class Store(
    private val storage: AppStateStorage,
    private val apiClient: ApiClient
)
```

Async operations use `viewModelScope` directly inside Store (the calling ViewModel provides the scope implicitly via CoroutineScope interface, or Store receives a `CoroutineScope` parameter).

Preferred: Store receives `CoroutineScope`:

```kotlin
class Store(
    private val scope: CoroutineScope,
    private val storage: AppStateStorage,
    private val apiClient: ApiClient
) {
    init {
        scope.launch {
            appState.collect { state ->
                storage.save(state)
            }
        }
    }
}
```

ViewModel:

```kotlin
class AppViewModel(storage: AppStateStorage, api: ApiClient) : ViewModel() {
    private val store = Store(viewModelScope, storage, api)
}
```

## Action Handler Structure

```kotlin
fun handle(action: AppAction) {
    when (action) {
        // Synchronous updates
        is AppAction.SetX -> appState.update { it.copy(x = action.value) }

        // Async operations
        is AppAction.LoadData -> scope.launch {
            appState.update { it.copy(isLoading = true) }
            try {
                val data = apiClient.fetch()
                appState.update { it.copy(data = data, isLoading = false) }
            } catch (e: Exception) {
                appState.update { it.copy(error = e.message, isLoading = false) }
            }
        }

        // Navigation side effects
        is AppAction.GoToDetail -> {
            appState.update { it.copy(selectedId = action.id) }
            _nav.trySend(Routes.DETAIL)
        }

        // Reset
        is AppAction.Reset -> {
            appState.value = AppState()
            storage.save(AppState())
            _nav.trySend(Routes.HOME)
        }
    }
}
```

## State Access Patterns

### Read current snapshot

```kotlin
store.appState.value.currentDiet
```

### Observe as Compose state

```kotlin
val state by viewModel.state.collectAsState()
```

### Pass to child composable

```kotlin
SomeScreen(
    appState = state,
    sendAction = viewModel::send
)
```

## Side Effects Design

Side effects must be **consumed once**. Use `Channel` not `SharedFlow`:

```kotlin
private val _nav = Channel<String?>(capacity = Channel.UNLIMITED)
val nav = _nav.receiveAsFlow()
```

- `String?` — non-null = navigate forward, null = pop back
- `UNLIMITED` capacity — prevents blocking if UI not collecting

Other side effects (toast, snackbar):

```kotlin
private val _effects = Channel<Effect>(capacity = Channel.UNLIMITED)
val effects = _effects.receiveAsFlow()

sealed class Effect {
    data class Toast(val message: String) : Effect()
    data class Notification(val title: String, val body: String) : Effect()
}
```

## Testing

Store is a plain class — easy to unit test:

```kotlin
@Test
fun `load data updates state`() = runTest {
    val store = Store(this, FakeStorage(), FakeApi())

    store.handle(AppAction.LoadData)

    assertEquals(true, store.appState.value.isLoading)
    advanceUntilIdle()
    assertEquals(listOf(...), store.appState.value.items)
}
```

## Multi-Store Architecture

For large apps, split by feature:

```kotlin
class AppViewModel(val global: GlobalStore, val fasting: FastingStore) : ViewModel()
```

Or use single Store with nested state:

```kotlin
data class AppState(
    val fasting: FastingState = FastingState(),
    val settings: SettingsState = SettingsState()
)
```

Single Store is simpler for mini-apps. Multi-store for large modular apps.
