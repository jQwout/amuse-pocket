# Navigation in MVI

## Principle

Navigation is a **side effect**, not a state property. Route destination lives in Store's `Channel`, not in `AppState`.

## Why Not State-Based Navigation?

- `currentRoute` in state = UI reads state -> navigates -> state becomes stale if back button pressed
- Back button is a system event, not an Action
- Deep links also bypass state

## Implementation

### ViewModel side

Expose navigation flow from ViewModel:

```kotlin
class AppViewModel(storage: AppStateStorage) : ViewModel() {
    private val store = Store(viewModelScope, storage)
    val state: StateFlow<AppState> = store.appState.asStateFlow()
    val nav = store.nav
    fun send(action: AppAction) = store.handle(action)
}
```

### Store side

```kotlin
private val _nav = Channel<String?>(capacity = Channel.UNLIMITED)
val nav = _nav.receiveAsFlow()

// Navigate forward
_nav.trySend(Routes.DETAIL)

// Navigate back
_nav.trySend(null)
```

### NavGraph side

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

## Back Button

```kotlin
BackHandler {
    viewModel.send(AppAction.NavBack())
}
```

NavBack action in Store:

```kotlin
is AppAction.NavBack -> _nav.trySend(null)
```

## Parameter Passing

Store holds selected item ID in state, screen reads it:

```kotlin
// Action
data class SelectItem(val id: String) : AppAction()

// Handler
is AppAction.SelectItem -> {
    appState.update { it.copy(selectedId = action.id) }
    _nav.trySend(Routes.DETAIL)
}

// Screen
val item = repository.find(state.selectedId)
```

No navigation arguments needed.

## Bottom Bar / Tabs

Tab switching is **state**, not navigation:

```kotlin
data class AppState(val selectedTab: Int = 0)

sealed class AppAction {
    data class SelectTab(val index: Int) : AppAction()
}
```

Screen:

```kotlin
var selectedTab by remember { mutableStateOf(0) } // WRONG

// CORRECT — state driven
val state by viewModel.state.collectAsState()
when (state.selectedTab) { ... }
```
