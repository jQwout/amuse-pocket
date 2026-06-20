---
name: mvi-compose-kotlin
description: "MVI (Model-View-Intent) architecture for Android with Jetpack Compose and Kotlin. Provides a unidirectional data flow pattern with immutable State, sealed Actions, a central Store inside a ViewModel, and Channel-based side effects. Use when (1) building a new Android Compose screen or feature, (2) refactoring existing MVVM/MVP code to MVI, (3) creating a mini-app module with predictable state management, (4) implementing state persistence and navigation side effects, (5) the user mentions MVI, unidirectional flow, stateless UI, or asks for a clean architecture pattern for Compose."
---

# MVI Compose Kotlin

Unidirectional data flow architecture for Android with Jetpack Compose.

## Core Concepts

- **State** — immutable `data class`, single source of truth
- **Action** — `sealed class` describing everything that can happen
- **Store** — plain class holding `MutableStateFlow<State>` and `handle(action)` dispatcher
- **ViewModel** — Android `ViewModel` that owns the Store and survives config changes
- **UI** — stateless `@Composable` receiving `State` + `SendAction`
- **Side Effects** — `Channel<T>` for navigation, toasts, notifications (consumed once)
- **Persistence** — JSON serialization via kotlinx.serialization, auto-saved on state changes

## Dependencies

Add to `build.gradle.kts`:

```kotlin
dependencies {
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.navigation:navigation-compose:2.8.0")
}

plugins {
    kotlin("plugin.serialization") version "2.0.0"
}
```

## File Structure

```
com.example.app/
├── MainActivity.kt              // ViewModel init + setContent
├── state/
│   ├── AppState.kt              // data class + sealed Action
│   ├── AppStateStorage.kt       // JSON read/write
│   └── AppViewModel.kt          // ViewModel hosting Store
├── ui/
│   ├── screens/
│   │   └── HomeScreen.kt        // Stateless composable
│   └── theme/
│       └── ...
└── navigation/
    └── NavGraph.kt              // Collects viewModel.nav + routes
```

## Rules

### 1. State is Immutable

```kotlin
data class AppState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
```

Update via `copy()` inside `Store.handle()`:

```kotlin
appState.update { it.copy(isLoading = true) }
```

### 2. Actions are Sealed

```kotlin
sealed class AppAction {
    data class LoadItems(val force: Boolean = false) : AppAction()
    data class ItemClicked(val id: String) : AppAction()
    data object Refresh : AppAction()
    data object NavBack : AppAction()
}
```

### 3. Store Pattern

```kotlin
class Store(
    private val storage: AppStateStorage
) {
    val appState = MutableStateFlow(AppState())
    private val _nav = Channel<String?>(capacity = Channel.UNLIMITED)
    val nav = _nav.receiveAsFlow()

    init {
        viewModelScope.launch {
            appState.collect { storage.save(it) }
        }
    }

    fun handle(action: AppAction) {
        when (action) {
            is AppAction.LoadItems -> { /* ... */ }
            is AppAction.NavBack -> _nav.trySend(null)
        }
    }
}
```

### 4. ViewModel Hosts Store

```kotlin
class AppViewModel(
    private val storage: AppStateStorage
) : ViewModel() {
    private val store = Store(storage)

    val state: StateFlow<AppState> = store.appState.asStateFlow()
    val nav = store.nav

    fun send(action: AppAction) = store.handle(action)
}
```

Factory for default construction:

```kotlin
class AppViewModelFactory(private val storage: AppStateStorage) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppViewModel(storage) as T
    }
}
```

### 5. UI is Stateless

```kotlin
typealias SendAction = (AppAction) -> Unit

@Composable
fun HomeScreen(
    appState: AppState,
    sendAction: SendAction
) {
    // NO remember/rememberSaveable for business state
    // Read everything from appState
}
```

### 6. Navigation via Side Effect Channel

In `NavGraph.kt`:

```kotlin
@Composable
fun NavGraph(viewModel: AppViewModel, navController: NavHostController) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.nav.collect { route ->
            if (route != null) navController.navigate(route)
            else navController.popBackStack()
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                appState = state,
                sendAction = viewModel::send
            )
        }
    }
}
```

### 7. Persistence Auto-Wired

Store saves state on every update via `appState.collect`. Load on startup:

```kotlin
// MainActivity.onCreate
viewModel.send(AppAction.LoadData)
```

## Full Example

See `assets/boilerplate/ScreenModule.kt` for a complete starter module.

## Anti-Patterns

- Do NOT use `remember { mutableStateOf(...) }` for business logic state
- Do NOT call Android framework APIs directly from composables
- Do NOT put navigation logic inside composables — route via Actions
- Do NOT use `var` in State — always `val` + `copy()`
- Do NOT expose `MutableStateFlow` outside Store
- Do NOT use `rememberSaveable` for business state — ViewModel survives config changes

## Integration with Mini-Apps

When adding a new mini-app to an existing project:

1. Define `MiniAppState` and `MiniAppAction` in the mini-app package
2. Create `MiniAppStore` following the base Store pattern
3. Host in `MiniAppViewModel` extending `ViewModel`
4. Pass `viewModel` into mini-app entry composable
5. Mini-app actions can bubble up to parent or be handled locally

## References

- [references/store-pattern.md](references/store-pattern.md) — detailed Store implementation guide
- [references/navigation-mvi.md](references/navigation-mvi.md) — navigation patterns with MVI
- [references/state-persistence.md](references/state-persistence.md) — JSON serialization strategies
