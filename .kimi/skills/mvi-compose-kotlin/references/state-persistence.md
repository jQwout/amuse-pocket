# State Persistence

## kotlinx.serialization

Requires Gradle plugin `kotlin("plugin.serialization")` and dependency `kotlinx-serialization-json`.

```kotlin
import kotlinx.serialization.Serializable

@Serializable
data class AppState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false
)
```

## JSON File Storage

```kotlin
class JsonFileStorage(private val context: Context) {
    fun save(filename: String, json: String) {
        context.openFileOutput(filename, Context.MODE_PRIVATE).use {
            it.write(json.toByteArray())
        }
    }

    fun load(filename: String): String? {
        return try {
            context.openFileInput(filename).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            null
        }
    }
}
```

## AppStateStorage

```kotlin
import kotlinx.serialization.json.Json

class AppStateStorage(private val context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    private val storage = JsonFileStorage(context)

    fun save(state: AppState) {
        storage.save("app_state", json.encodeToString(state))
    }

    fun load(): AppState? {
        return storage.load("app_state")?.let {
            json.decodeFromString(it)
        }
    }
}
```

## Auto-Save in Store

```kotlin
init {
    scope.launch {
        appState.collect { state ->
            storage.save(state)
        }
    }
}
```

## Migration Strategy

Add `@SerialName` and default values for new fields:

```kotlin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppState(
    val items: List<Item> = emptyList(),
    val isLoading: Boolean = false,
    @SerialName("new_field")
    val newField: String = ""  // default for old saves
)
```

`ignoreUnknownKeys = true` handles removed fields.

## Versioning

For major schema changes, store version in JSON:

```kotlin
data class AppStateWrapper(
    val version: Int = 1,
    val state: AppState
)
```
