# AI Open Pocket — Agent Guide

> **Project type:** Android application (single-module)  
> **Primary language:** Kotlin  
> **UI framework:** Jetpack Compose + Material 3  
> **Build system:** Gradle with Kotlin DSL  

---

## Project Overview

AI Open Pocket is an Android AI companion app that acts as a pocket-sized client for large language models. The app communicates with the **NVIDIA NIM API** (default model: `meta/llama-3.1-70b-instruct`) and features a secured local SQLite database, cookie state extraction, an elegant forced-dark Material 3 design, and a "Driven UI" engine that renders interactive cards (todo lists, weather, timers, charts, tables, forms) when the LLM returns structured JSON.

The project was originally exported from Google AI Studio. The app UI is in **Russian**, while code comments and documentation are in **English**.

**Application ID:** `com.aistudio.aiopenpocket.hzwpqy`  
**Package namespace:** `com.example` (still uses the default template package)  
**Min SDK:** 24  
**Target/Compile SDK:** 36 (minor API level 1)

---

## Technology Stack

| Layer | Libraries |
|-------|-----------|
| **Language** | Kotlin 2.2.10 |
| **UI** | Jetpack Compose (BOM 2024.09.00), Material 3, Activity Compose |
| **Architecture** | MVVM, single-Activity |
| **DI** | Metro 1.1.1 (via Zac Sweers) — *not* Dagger/Hilt |
| **Networking** | Ktor Client 2.3.11 (Android engine), Retrofit + OkHttp + Moshi (also on classpath) |
| **Database** | Room 2.7.0 (KSP codegen) |
| **Serialization** | Kotlinx Serialization JSON 1.6.3 |
| **Image loading** | Coil Compose 2.7.0 |
| **Coroutines** | Kotlinx Coroutines 1.10.2 |
| **Screenshot testing** | Roborazzi 1.59.0 |
| **Unit testing** | JUnit 4, Robolectric 4.16.1, Compose UI Test (Junit4) |

### Plugins (app-level)
- `com.android.application`
- `org.jetbrains.kotlin.plugin.compose`
- `com.google.devtools.ksp`
- `io.github.takahirom.roborazzi`
- `com.google.android.libraries.mapsplatform.secrets-gradle-plugin`
- `dev.zacsweers.metro`

---

## Project Structure

```
app/src/main/java/com/example/
├── AiOpenPocketApp.kt          # Application class; creates Metro AppGraph
├── MainActivity.kt              # Single Activity; hosts Compose UI
├── data/
│   ├── database/
│   │   ├── AppDatabase.kt       # Room DB (queen_chat_db, v1)
│   │   ├── ChatDao.kt           # DAO for messages + cookies
│   │   ├── ChatMessage.kt       # Entity: chat_messages
│   │   └── UserCookie.kt        # Entity: user_cookies (session_cookie)
│   ├── network/
│   │   └── NvidiaClient.kt      # Ktor client; calls integrate.api.nvidia.com
│   └── repository/
│       └── ChatRepository.kt    # Repository bridging DAO, SharedPrefs, and application context
├── di/
│   ├── AppGraph.kt              # Metro @DependencyGraph (root graph + ViewModelGraph)
│   ├── AppScope.kt              # @Scope annotation
│   ├── AppViewModelFactory.kt   # MetroX ViewModel factory binding
│   └── DatabaseBindings.kt      # Provides Room DB and ChatDao
└── ui/
    ├── chat/
    │   ├── ChatScreen.kt        # Main Compose screen (~959 lines)
    │   ├── ChatViewModel.kt     # ViewModel with StateFlow + ChatIntent handling
    │   ├── ChatUiState.kt       # State, AttachmentInfo, ChatIntent sealed interface
    │   └── DrivenUiEngine.kt    # JSON-to-Compose card renderer (todo, weather, timer, chart, table, form)
    └── theme/
        ├── Color.kt             # Dark palette (forced dark theme)
        ├── Theme.kt             # MyApplicationTheme (dark only, dynamic color disabled)
        └── Type.kt              # Typography

app/src/main/assets/personas/
├── attentive_secretary.md
├── egalitarian_partner.md
├── friendly_companion.md
├── ironic_assistant.md
├── sharp_partner.md          # Default persona
├── silent_butler.md
└── strict_coach.md

app/src/main/assets/config/
└── models.json               # Ranked NVIDIA NIM model catalog (model selection source)
```

---

## Build & Run

### Prerequisites
- Android Studio (latest stable recommended)
- JDK 17+ (Android Gradle Plugin 9.1.1 requirement)

### Environment setup
Create a `.env` file in the project root (see `.env.example`):

```
GEMINI_API_KEY=MY_GEMINI_API_KEY
NVIDIA_API_KEY=MY_NVIDIA_API_KEY
```

The **Secrets Gradle Plugin** reads `.env` and injects values into `BuildConfig`. `NVIDIA_API_KEY` is preferred; `GEMINI_API_KEY` is used as a fallback if the NVIDIA key is missing or still set to the placeholder.

### Gradle commands

```bash
# Build debug APK
./gradlew :app:assembleDebug

# Run unit tests (including Robolectric)
./gradlew :app:testDebugUnitTest

# Run instrumented tests (requires connected device/emulator)
./gradlew :app:connectedAndroidTest

# Run screenshot tests (Roborazzi)
./gradlew :app:verifyRoborazziDebug
```

### Signing
- **Debug builds** use `debug.keystore` (decoded from `debug.keystore.base64` at the project root).  
  The debug config is referenced explicitly in `build.gradle.kts` as `signingConfig = signingConfigs.getByName("debugConfig")`.  
  For external builds you may need to remove this line if the keystore is missing.
- **Release builds** expect environment variables: `KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_PASSWORD`, key alias `upload`.

---

## Architecture Details

### Dependency Injection (Metro)
The project uses **Metro** rather than Hilt/Dagger:
- `@DependencyGraph(AppScope::class)` on `AppGraph`
- `@ContributesTo(AppScope::class) @BindingContainer` for module objects (e.g., `DatabaseBindings`)
- `@Inject` constructors for ViewModels and singletons
- `@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())` + `@ViewModelKey(ChatViewModel::class)` to register ViewModels
- `LocalMetroViewModelFactory` is provided in Compose via `CompositionLocalProvider`
- ViewModels are obtained in Compose with `metroViewModel()`

### State Management
- **ChatUiState** is a plain data class held in a `MutableStateFlow` inside `ChatViewModel`.
- **ChatIntent** is a sealed interface representing all user actions (`SendMessage`, `CancelRequest`, `AttachMockFile`, `SaveApiKey`, `ChangePersona`, `ClearHistory`, `Logout`, etc.).
- The ViewModel converts intents into coroutine jobs; active LLM requests are stored in `activeLlmJob` and can be cancelled.

### Data Flow
1. User sends message → `ChatIntent.SendMessage` → ViewModel inserts user message into Room → launches Ktor request to NVIDIA NIM → inserts AI response into Room.
2. UI collects `allMessages` as `Flow<List<ChatMessage>>` and auto-scrolls.
3. History is limited to the last 20 messages when building the LLM context to stay within token limits.

### Driven UI Engine
`DrivenUiEngine.kt` parses JSON blocks returned by the LLM and renders native Compose cards:
- `todo_list` — interactive checklist
- `weather_card` — multi-day forecast with icons
- `timer` — countdown timer with play/pause/reset
- `chart_bar` — bar chart drawn with `Canvas`
- `table` — scrollable data table
- `form` — dynamic input form with submit

The LLM is instructed via a large system prompt (in `ChatViewModel`) to return **only** JSON when a visual result is appropriate.

### Personas
Seven personas are stored as markdown files in `assets/personas/`. The default is `sharp_partner` (business-like, proactive tone). The persona content is loaded from assets and injected into the system prompt at runtime.

### Model Catalog & Selection
The working LLM is **chosen from a ranked catalog**, not hard-coded. The catalog lives at `app/src/main/assets/config/models.json` and is the single source of truth for which NVIDIA NIM models the agent may use.

Each entry has four fields:

| Field | Meaning |
|-------|---------|
| `name` | NVIDIA NIM model id (e.g. `deepseek-ai/deepseek-v4-flash`), sent as the `model` field to `https://integrate.api.nvidia.com/v1`. |
| `priority` | Selection order — **1 is tried first**, then 2, 3, 4 as fallbacks if a higher-priority model is unavailable or errors. |
| `max_tokens` | Per-request `max_tokens` ceiling for that model. |
| `reasoning` | `true` if the model supports a thinking/reasoning mode (requires the corresponding `extra_body` flag, e.g. `chat_template_kwargs.thinking` or `reasoning_budget`); `false` for plain chat models. |

Current ranking (highest priority first):

1. `deepseek-ai/deepseek-v4-flash` — 16384 tokens, reasoning
2. `meta/llama-3.1-8b-instruct` — 1024 tokens, no reasoning
3. `openai/gpt-oss-20b` — 4096 tokens, no reasoning
4. `nvidia/nemotron-3-nano-30b-a3b` — 16384 tokens, reasoning

**Runtime wiring.** At graph construction `LlmModule.provideProviderFallbackChain` calls `ModelCatalogLoader.load()` (reads + parses `models.json`, sorts by `priority`) and expands the catalog into one `ProviderConfig` per model under `LlmProvider.NIM`. `ProviderFallbackChain.resolve(AUTO)` returns those NIM models in priority order, followed by the GROQ and GITHUB fallbacks. `OpenAiCompatClient.streamChat` iterates the resolved list and moves to the next entry whenever a request errors (blank key, non-2xx, network) — so a higher-priority model failing transparently falls through to the next. Each request sends that model's `max_tokens` from the catalog. If `models.json` is missing or empty, the loader falls back to a single safe model (`meta/llama-3.1-70b-instruct`).

To change which model the agent uses, edit `models.json` and re-rank by `priority` — no code change required.

> Note: the `reasoning` flag is loaded and carried on `ProviderConfig`, but the model-specific reasoning `extra_body` (DeepSeek `chat_template_kwargs.thinking`, Nemotron `reasoning_budget`) is **not** emitted yet — only `max_tokens` and model selection are wired.

### System Integrations & Permissions
Four agent tools expose Android system features to the LLM (under `app/src/main/java/com/example/agent/tools/`, registered in `di/AgentToolsModule.kt` via `@Provides @IntoSet`):

| Tool (`name`) | Actions | Android API | Permission |
|---|---|---|---|
| `CalendarTool` (`calendar`) | `list_events`, `create_event`, `delete_event` | `CalendarContract` (Instances/Events/Calendars) | `READ_CALENDAR`, `WRITE_CALENDAR` (runtime) |
| `SmsTool` (`sms`) | `read_recent` | `Telephony.Sms.Inbox` content provider | `READ_SMS` (runtime) |
| `AlarmTool` (`alarm`) | `set_alarm`, `show_alarms` | `AlarmClock.ACTION_SET_ALARM` / `ACTION_SHOW_ALARMS` intents | `SET_ALARM` (normal, auto-granted) |
| `EmailTool` (`email`) | `compose` | `Intent.ACTION_SENDTO` (`mailto:`) | none — composer only, no reading |

**Permission model.** Tools run on a background dispatcher inside `AgentLoopOrchestrator` and therefore cannot launch the runtime-permission dialog themselves. Instead:
- The user grants permissions ahead of time in **Settings → ИНТЕГРАЦИИ** (`IntegrationsSection` in `SettingsScreen.kt`), which uses `ActivityResultContracts.RequestMultiplePermissions` launchers and re-reads grant status on `Lifecycle.Event.ON_RESUME` (so it updates after returning from the system settings screen).
- Each tool calls the shared `Context.requirePermission(...)` helper in `agent/tools/PermissionUtils.kt` and returns `ToolResult.Error(..., recoverable = false)` with a Russian "выдайте разрешение в Настройках" message when a permission is missing.

All four tools are documented for the LLM under the **"Системные интеграции"** section of `assets/prompts/system_base.md`. Manifest declares the four permissions above. Reading email (Gmail API / OAuth) is intentionally **not** implemented — `EmailTool` only opens a pre-filled draft for the user to send manually.

> Note: `READ_SMS` is restricted on the Play Store (requires default-SMS-handler status or a special declaration). It works normally for personal/sideload builds — this is a publishing constraint, not a development one.

---

## Code Style Guidelines

- **Kotlin code style:** `official` (set in `gradle.properties`).
- **Indent:** 2 spaces (observed in the existing source).
- **Trailing commas:** Generally used.
- **Imports:** Use explicit imports; no wildcard imports.
- **Compose conventions:**
  - Use `Modifier.testTag("...")` on interactive elements for UI testing.
  - Theming uses `MaterialTheme.colorScheme.*` and custom palette in `Color.kt`.
  - Theme is forced dark (`darkTheme = true`) with dynamic color disabled.
- **Language mixing:** UI strings inside Compose are in **Russian**. Log tags, comments, class names, and documentation are in **English**.

---

## Testing Strategy

The project has a minimal but multi-layered testing setup:

1. **Unit tests** (`src/test/java`)
   - `ExampleUnitTest.kt` — basic JUnit sanity check.
   - `ExampleRobolectricTest.kt` — Robolectric tests that run Android components on the JVM (context reads, Activity launch).
   - `GreetingScreenshotTest.kt` — Roborazzi screenshot test using `createComposeRule()` and `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.

2. **Instrumented tests** (`src/androidTest/java`)
   - `ExampleInstrumentedTest.kt` — Espresso-style context verification on a real device/emulator.

### Running tests
```bash
# JVM unit tests (includes Robolectric)
./gradlew :app:testDebugUnitTest

# Screenshot record/verify
./gradlew :app:recordRoborazziDebug
./gradlew :app:verifyRoborazziDebug

# Device tests
./gradlew :app:connectedDebugAndroidTest
```

**Current test coverage is very low.** Adding tests for `ChatViewModel`, `NvidiaClient`, and `DrivenUiEngine` would be valuable.

---

## Security Considerations

- **API keys** are injected via the Secrets Gradle Plugin from `.env`. They become `BuildConfig` fields at compile time. This is **not** obfuscated in the APK; treat debug builds as containing plaintext secrets.
- **Custom API keys** entered by the user are stored in **SharedPreferences** (`ai_pocket_preferences`) under `custom_nvidia_api_key`. This is unencrypted standard prefs.
- **Session cookies** are stored in the local Room database (`user_cookies` table) with a single-row design (`id = 'session_cookie'`).
- **Debug keystore** is committed to the repo as `debug.keystore.base64`. Do **not** use this for release builds.
- **Network security:** The app calls `https://integrate.api.nvidia.com`. No certificate pinning is implemented.
- **ProGuard/R8:** `isMinifyEnabled = false` in both build types currently. No code shrinking or obfuscation is applied.

---

## Key Files for Agents

| File | Purpose |
|------|---------|
| `app/build.gradle.kts` | Module build config, dependencies, signing, secrets plugin setup |
| `gradle/libs.versions.toml` | Version catalog for all dependencies |
| `.env` / `.env.example` | API key secrets (not committed) |
| `app/src/main/java/com/example/ui/chat/ChatViewModel.kt` | Core business logic, LLM prompt construction, intent handling |
| `app/src/main/java/com/example/data/network/NvidiaClient.kt` | HTTP client and API key resolution logic |
| `app/src/main/java/com/example/di/AppGraph.kt` | Metro DI root graph |
| `app/src/main/java/com/example/ui/chat/DrivenUiEngine.kt` | Custom JSON-to-Compose rendering engine |
| `debug.keystore.base64` | Base64-encoded debug keystore |

---

## Notes for AI Agents

- Do **not** assume Hilt/Dagger conventions. This project uses **Metro** (`dev.zacsweers.metro`). Use `@Inject`, `@DependencyGraph`, `@ContributesTo`, `@ContributesBinding`, and `@ContributesIntoMap` accordingly.
- The package name is still `com.example` everywhere. If refactoring to the real application ID, update `namespace` in `app/build.gradle.kts` and all package declarations.
- The UI is almost entirely in Russian. If adding new user-visible strings, continue using Russian for consistency unless instructed otherwise.
- The app forces a dark theme. Avoid adding light-theme-specific colors unless explicitly requested.
- When modifying the LLM system prompt (inside `ChatViewModel`), preserve the JSON template examples so `DrivenUiEngine` can still parse responses correctly.
