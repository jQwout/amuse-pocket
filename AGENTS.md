# AI Open Pocket — Agent Guide

> **Project type:** Android application (single-module)  
> **Primary language:** Kotlin  
> **UI framework:** Jetpack Compose + Material 3  
> **Build system:** Gradle with Kotlin DSL  

---

## Project Overview

AI Open Pocket is an Android AI companion app: a pocket-sized, multi-agent client for large language models. It talks to an OpenAI-compatible chat-completions API through a ranked, fallback-aware provider chain (NVIDIA NIM primary, GROQ and GitHub Models as fallbacks), persists conversations/messages/facts/episodes in a local Room database, and renders interactive UI cards (todo lists, weather, timers, charts, tables, forms, lesson widgets, etc.) whenever the LLM explicitly calls a `render_card` tool.

Beyond a single chatbot, the app now ships:
- A **roster of 6 domain mini-agents** (budget, languages, tasks, reminders, nutrition, diary), each scoped to its own tool/skill set.
- A **skill system** for progressive-disclosure domain instructions (`SKILL.md` files loaded on demand via a `load_skill` tool).
- An **"AI Academy" onboarding/roadmap**: a JSON-driven sequence of lessons that teach the user LLM concepts through structured, quizzed chat interactions.
- **Voice input**: both a system-wide Android voice assistant integration and an in-chat microphone button.
- **Background/scheduled tasks** via WorkManager, with a user-facing "scheduled tasks" screen.

The project was originally exported from Google AI Studio. The app UI is primarily in **Russian** (the user can switch languages in Settings). Code comments and documentation are nominally in **English**, but in practice many files mix in Russian explanatory comments/KDoc (e.g. `PrePromptAssembler.kt`, `ProviderFallbackChain.kt`) alongside English `// @trace <feature-id>` markers (see [Traceability](#traceability) below) — don't assume every comment you meet will be English.

**Application ID:** `com.aistudio.aiopenpocket.hzwpqy`  
**Gradle namespace:** `com.aiopenpocket`  
**Min SDK:** 24  
**Target/Compile SDK:** 36  
**JVM target:** 21  

> **Package namespace gotcha.** Every Kotlin file's `package` declaration has already been migrated to `com.aiopenpocket.*` — verified: **zero** files anywhere under `app/src/main` declare `com.example.*`. But the **physical directory layout hasn't caught up**: most existing files (134 of them) still live on disk under `app/src/main/java/com/example/...` while declaring `package com.aiopenpocket....`. This compiles fine (Kotlin doesn't require path/package correspondence), but it means **you cannot infer a file's package from its directory path**, and any tooling that assumes they match will be confused. Newly-added code — the `voice/` feature, and *all* test sources — is already placed correctly under `com/aiopenpocket/...`, matching its declared package. Follow that convention for new files rather than adding more to `com/example/`.

---

## Technology Stack

| Layer | Libraries |
|-------|-----------|
| **Language** | Kotlin 2.3.0 |
| **UI** | Jetpack Compose (BOM 2026.05.00), Material 3, Activity Compose, Navigation Compose |
| **Architecture** | MVVM, single-Activity, ReAct-style agent loop |
| **DI** | Metro 1.1.1 (via Zac Sweers) — *not* Dagger/Hilt |
| **Networking** | Ktor Client 2.3.11 (Android engine; version pinned inline in `build.gradle.kts`, not in the version catalog); Retrofit + OkHttp + Moshi also on classpath |
| **Database** | Room 2.7.0 (KSP codegen); db `queen_chat_db`, currently at schema version 9 |
| **Serialization** | Kotlinx Serialization JSON 1.6.3 |
| **Image loading** | Coil Compose 2.7.0 (+ Coil SVG) |
| **Coroutines** | Kotlinx Coroutines 1.10.2 |
| **Background work** | AndroidX WorkManager (scheduled/periodic notifications) |
| **Secure storage** | AndroidX Security Crypto (`EncryptedSharedPreferences`, AES256_GCM) for API keys; AndroidX DataStore Preferences for UI/onboarding prefs |
| **Markdown rendering** | mikepenz `multiplatform-markdown-renderer` (+ Material 3 flavor) |
| **Screenshot testing** | Roborazzi 1.59.0 |
| **Unit testing** | JUnit 4, Robolectric 4.16.1, Compose UI Test (JUnit4), Kotlinx Coroutines Test |

### Plugins (app-level)
- `com.android.application` (AGP 8.9.1)
- `org.jetbrains.kotlin.android`
- `org.jetbrains.kotlin.plugin.compose`
- `org.jetbrains.kotlin.plugin.serialization`
- `com.google.devtools.ksp`
- `io.github.takahirom.roborazzi`
- `com.google.android.libraries.mapsplatform.secrets-gradle-plugin`
- `dev.zacsweers.metro`

---

## Project Structure

```
app/src/main/java/com/example/          # physical directory; ALL files below declare `package com.aiopenpocket.*` (see gotcha above)
├── AiOpenPocketApp.kt           # Application class; builds the Metro AppGraph
├── MainActivity.kt              # Single Activity; NavHost, ACTION_ASSIST intent handling, notification permission
├── agent/
│   ├── core/
│   │   ├── agent/                # MiniAgent.kt — roster data class + MiniAgentCatalog (6 domain agents)
│   │   ├── identity/              # UserPreferencesRepository.kt — DataStore: onboarding, persona, language, roadmap progress, goal-mode iteration cap
│   │   ├── lesson/                # Lesson.kt, LessonRepository.kt, LessonChatController.kt — AI Academy roadmap content
│   │   ├── llm/                   # ChatMessage, LlmClient, LlmProvider, ModelCatalog(+Loader), OpenAiCompatClient, ProviderFallbackChain, StreamEvent, FunctionSchema, LlmContextConfig
│   │   ├── mcp/                   # Tool, ToolExecutor, ToolRegistry, ToolResult, ToolSchemaBuilder — generic tool-calling framework
│   │   ├── memory/                # entity/ dao/ model/ repository/ — Room-backed conversations, messages, tags, facts, episodes
│   │   ├── notification/          # NotificationHelper.kt — shared notification-posting helper
│   │   ├── persona/               # PersonaCatalog.kt — 7 swappable personas (stats/perks/quotes)
│   │   ├── profile/               # ExtractUserProfileUseCase.kt — LLM-driven onboarding profile extraction
│   │   ├── prompt/                # PrePromptAssembler, ContextWindowBuilder, ConversationAnnotator
│   │   └── skill/                 # Skill.kt, SkillRegistry, AssetSkill, LoadSkillTool, skills/{Budget,Diary,Fitness,Languages}Skill.kt
│   ├── loop/                      # AgentLoopOrchestrator (main entry, CHAT/PLAN/GOAL modes), ReactLoopRunner (shared ReAct engine), AgentMode, Plan, PlanExecuteOrchestrator (wired but not called from the main flow)
│   ├── tools/                     # 12 concrete Tool implementations exposed to the LLM (see System Integrations below) + PermissionUtils, ToolDateFormat
│   └── workers/                   # NotificationWorker.kt — WorkManager Worker for scheduled/periodic notifications
├── data/
│   ├── database/AppDatabase.kt    # Room DB (queen_chat_db, v9, fallbackToDestructiveMigration)
│   └── repository/SettingsRepository.kt  # Encrypted API keys + selected provider/model (EncryptedSharedPreferences)
├── di/                            # Metro graph: AppGraph, AppScope, AppViewModelFactory, DatabaseBindings, LlmModule, SkillModule, AgentToolsModule, UserPreferencesModule
└── ui/
    ├── agents/                    # Mini-agent roster screen
    ├── chat/                      # ChatScreen/ChatViewModel/DrivenUiEngine/ChatIntent/ChatUiState + components/ (MicButton, ToolTracePanel)
    ├── components/                # AcademyBottomNav, AcademyCollapsingScaffold, MarkdownText, StreamingBubble
    ├── conversations/             # Past-conversations list
    ├── home/                      # HomeScreen — actual post-onboarding entry point (chat is one destination among several)
    ├── memory/                    # Force-directed memory graph visualization
    ├── onboarding/                # First-run flow (language → persona → profile → intro) + components/
    ├── roadmap/                   # AI Academy: RoadmapScreen, LibraryScreen, QuizScreen + components/
    ├── scheduledworks/            # User-facing list of WorkManager scheduled tasks
    ├── settings/                  # API keys, provider/model choice, persona, permissions, goal-mode iteration cap
    └── theme/                     # Color.kt / Theme.kt (forced dark, no light palette) / Type.kt (Lora serif headings)

app/src/main/java/com/aiopenpocket/    # NEW code lands here directly — directory matches declared package
└── voice/
    ├── AssistInteractionService.kt / AssistSessionService.kt / AssistSession.kt / AssistRecognitionService.kt   # system-wide Android voice-assistant integration (VoiceInteractionService)
    └── input/
        ├── VoiceInputController.kt / VoiceInputState.kt   # in-chat SpeechRecognizer wrapper (drives MicButton)
        └── LocaleMapping.kt                                # app language code → BCP-47 locale for recognition

app/src/main/assets/
├── config/models.json             # Ranked LLM model catalog (source of truth for model selection — see below)
├── lessons/*.json                 # 9 AI-Academy lesson scripts (typed steps: text/image/quiz/reveal/concept/example/fill/sort/match/scenario)
├── personas/*.md                  # 7 personas (see Personas below)
├── prompts/                       # system_base.md, tutorial.md, roadmap/{l1_welcome..l7_combo,plan_mode}.md
└── skills/{budget,diary,fitness,languages}/SKILL.md
```

---

## Build & Run

### Prerequisites
- Android Studio (latest stable recommended)
- JDK 21+ (Kotlin/Java compile target is 21; Android Gradle Plugin is 8.9.1)

### Environment setup
Create a `.env` file in the project root (see `.env.example`):

```
GEMINI_API_KEY=MY_GEMINI_API_KEY
NVIDIA_API_KEY=MY_NVIDIA_API_KEY
```

The **Secrets Gradle Plugin** reads `.env` and injects values into `BuildConfig`. `NVIDIA_API_KEY` is preferred; `GEMINI_API_KEY` is used as a fallback if the NVIDIA key is missing or still set to the placeholder. Per-provider keys can also be entered at runtime in Settings, where they're stored encrypted (see Security Considerations).

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
- `@DependencyGraph(AppScope::class)` on `AppGraph` (which extends Metrox's `ViewModelGraph`), with a `@DependencyGraph.Factory` for constructing it from `Application`
- `@ContributesTo(AppScope::class) @BindingContainer` object modules provide/bind dependencies — see `DatabaseBindings`, `LlmModule`, `SkillModule`, `AgentToolsModule`, `UserPreferencesModule`
- `@Inject` constructors for ViewModels and singletons; `@SingleIn(AppScope::class)` for scoped singletons
- `@ContributesBinding(AppScope::class)` for interface bindings (e.g. `AppViewModelFactory : MetroViewModelFactory()`)
- Multibinding via `@Provides @IntoSet` — used for `Set<Tool>` (12 domain tools + `LoadSkillTool`, contributed from both `AgentToolsModule` and `SkillModule`) and `Set<Skill>` (4 domain skills, from `SkillModule`)
- ViewModels are obtained in Compose via the Metrox `metroViewModel()` integration, backed by `AppViewModelFactory`'s provider/assisted-factory maps

### State Management
- **ChatUiState** is a plain data class held in a `MutableStateFlow` inside `ChatViewModel`.
- **ChatIntent** is a sealed interface representing user actions (send message, voice input, cancel, attach, change persona/mode, etc.).
- The ViewModel converts intents into coroutine jobs; active LLM turns are cancellable.

### Data Flow (single chat turn)
1. `ChatIntent.SendMessage` → `ChatViewModel` calls `AgentLoopOrchestrator.execute(conversationId, message, provider, mode, ...)`.
2. `PrePromptAssembler` builds the system prompt: base instructions + mode section (PLAN/GOAL, if active) + tutorial section + language directive + persona **or** mini-agent role + skill catalog (scoped to the mini-agent if bound) + user-data section (profile facts, relevant memory facts, recent episodes — marked untrusted) + a trimmed `ContextWindowBuilder` history window.
3. `ReactLoopRunner` runs the shared ReAct loop (reason → tool call → observe, up to `MAX_ITERATIONS = 10`), resolving tool calls through `ToolRegistry` (scoped to the mini-agent's `toolNames` when bound).
4. `OpenAiCompatClient.streamChat` sends the request via `ProviderFallbackChain.resolve(...)` (see Model Catalog below) and streams SSE deltas back; on failure *before* any content was emitted, it falls through to the next provider/model.
5. `ConversationRepository` persists messages to Room; `ConversationAnnotator` makes a background LLM call afterward to (re)derive the conversation's title/summary/tags.

### Driven UI Engine
`DrivenUiEngine.kt` renders native Compose cards from a `render_card` **tool call** (via `RenderCardTool`) — not raw JSON scanned out of chat text, as in the original AI-Studio-exported version. 19 templates are supported, split between general-purpose cards and AI-Academy lesson widgets:
- General: `todo_list`, `weather_card`, `timer`, `chart_bar`, `table`, `form`, `choice`, `switch`, `notification_permission`, `image`
- Lesson-flow: `lesson_quiz`, `lesson_complete`, `reveal`, `concept`, `example`, `fill`, `sort`, `match`, `scenario`

### Personas
`PersonaCatalog.kt` defines 7 personas, each with a display name, tone, 6 personality-stat dimensions (talkativeness, skepticism, criticism, proactivity, humor, empathy), perks, and example quotes; full instructions are still loaded from `assets/personas/{id}.md` at prompt-assembly time. The default is `sharp_partner` (business-like, proactive). Personas apply to plain chats; a mini-agent's own system prompt overrides the user's selected persona when the conversation is bound to one.

### Mini-Agent Roster
`MiniAgent` (`agent/core/agent/MiniAgent.kt`) is a data class (`id`, `name`, `description`, `iconKey`, `toolNames`, `systemPrompt`). `MiniAgentCatalog.all` defines 6 concrete domain agents, each scoped to its own tool subset: **budget**, **languages**, **tasks**, **reminders**, **nutrition**, **diary**. `AgentsScreen`/`AgentsViewModel` list them; tapping one finds-or-creates a conversation bound to that agent's `agentId`, which scopes both `ToolRegistry` (visible tools) and `SkillRegistry` (visible skills, via `load_skill`) for that conversation.

### Skill System
Skills are a progressive-disclosure layer on top of tools: `SkillRegistry` exposes lightweight `SkillManifest` metadata (id/description/whenToUse) in the system prompt for free; the model calls the `load_skill` tool (`LoadSkillTool`, registered like a normal `Tool`) to pull in the full `SKILL.md` instructions on demand. Four concrete skills extend `AssetSkill` and load from `assets/skills/{id}/SKILL.md`: **budget**, **diary**, **fitness**, **languages**. When a conversation is bound to a mini-agent, `load_skill` is restricted to that agent's own skill.

### Model Catalog & Selection
The working LLM is chosen from a ranked catalog, not hard-coded. The catalog lives at `app/src/main/assets/config/models.json`, loaded by `ModelCatalogLoader` (falls back to a single hardcoded `meta/llama-3.1-70b-instruct` entry if the asset is missing, unparseable, or empty).

Current catalog (all NIM models, `base_url = https://integrate.api.nvidia.com/v1`):

| priority | model | max_tokens | reasoning | notes |
|---|---|---|---|---|
| 1 | `deepseek-ai/deepseek-v4-flash` | 16000 | true | |
| 2 | `minimaxai/minimax-m3` | 16000 | true | `temperature=1.0`, `top_p=0.95` |
| 3 | `openai/gpt-oss-20b` | 16000 | false | |
| 4 | `meta/llama-3.1-8b-instruct` | 16000 | false | |
| 5 | `nvidia/nemotron-3-nano-30b-a3b` | 16000 | true | |
| 6 | `mistralai/mistral-medium-3.5-128b` | 16000 | true | |
| 7 | `mistralai/mistral-large-3-675b-instruct-2512` | 16000 | false | |

**Runtime wiring.** `LlmModule.provideProviderFallbackChain` expands the catalog into NIM `ProviderConfig`s and pairs them with the GROQ (`api.groq.com`) and GITHUB (`models.inference.ai.azure.com`) fallbacks. `LlmProvider` is `AUTO | NIM | GROQ | GITHUB`. `ProviderFallbackChain.resolve(AUTO)` returns `[NIM..., GROQ, GITHUB]` in that order; if the user picked a specific NIM model in Settings, that model is moved to the front of the NIM chain (the rest, plus GROQ/GITHUB, still follow as fallback). `OpenAiCompatClient` iterates this list and moves to the next entry on a non-2xx/network/parse error — but **only if no content was emitted yet** for the current attempt; once streaming has started, a later failure surfaces as `StreamEvent.Error` instead of silently retrying. Each request uses that model's `max_tokens` from the catalog.

To change which model the agent uses, edit `models.json` and re-rank by `priority` — no code change required.

> Note: `OpenAiCompatClient.kt` (`agent/core/llm/`) is the sole chat-completion client. The old `NvidiaClient.kt` referenced by earlier versions of this doc has been fully deleted, with zero remaining references anywhere in the codebase.

> Note: PLAN/GOAL mode are wired to layer an extra system-prompt section from `assets/prompts/modes/{plan_planning,plan_executing,goal}.md` (`PrePromptAssembler.loadModePrompt`), but those asset files don't currently exist on disk — `loadAsset()` fails silently (`runCatching { ... }.getOrNull()`), so those modes currently run **without** their dedicated instruction section. Worth fixing if you're touching PLAN/GOAL mode.

### AI Academy (Onboarding Roadmap)
A JSON-driven, linear lesson system separate from free chat. `Lesson.kt`/`LessonRepository.kt` load 9 scripts from `assets/lessons/*.json` (welcome, ReAct loop, chaining/routing, tokens & speed, privacy/reliability, arena & swarm, workflow & agent, how LLMs read text, plan mode); each lesson is a sequence of typed steps (`text`, `image`, `quiz`, `reveal`, `concept`, `example`, `fill`, `sort`, `match`, `scenario`) rendered as Driven UI cards and advanced one step per agent turn by `LessonChatController`. `RoadmapScreen` walks the 7 foundational lessons in order; `LibraryScreen` exposes the full catalog; `QuizScreen`/`QuizViewModel` handle end-of-lesson checks. Per-lesson system-prompt sections live in `assets/prompts/roadmap/l1_welcome.md` … `l7_combo.md`, plus a `plan_mode.md` variant.

### Voice Input
Two independent integrations, both under the (correctly-placed) `com.aiopenpocket.voice` package:
- **System voice assistant** (`AssistInteractionService`, `AssistSessionService`, `AssistSession`, `AssistRecognitionService`) registers the app as an Android `VoiceInteractionService`/`VoiceInteractionSessionService` (manifest-declared, `BIND_VOICE_INTERACTION`-protected). Invoking the system assistant opens `MainActivity` with an `ACTION_OPEN_NEW_CHAT` intent rather than showing its own overlay UI.
- **In-chat dictation** (`voice/input/VoiceInputController.kt`) wraps `android.speech.SpeechRecognizer` directly, exposing a `VoiceInputState` (Idle/Listening/Error) sealed interface and a transcript flow, driven by the `MicButton` composable in the chat input bar. `LocaleMapping.kt` maps the app's selected UI language to a BCP-47 recognition locale.

### System Integrations & Permissions
Twelve tools expose Android/system capabilities to the LLM (`app/src/main/java/com/example/agent/tools/`, all registered via `@Provides @IntoSet` in `di/AgentToolsModule.kt`; the `load_skill` meta-tool is registered separately in `di/SkillModule.kt`):

| Tool (`name`) | Actions | Android API | Permission |
|---|---|---|---|
| `calendar` | `list_events`, `create_event`, `delete_event` | `CalendarContract` (Instances/Events) | `READ_CALENDAR`, `WRITE_CALENDAR` (runtime) |
| `sms` | `read_recent` | `Telephony.Sms.Inbox` | `READ_SMS` (runtime) |
| `alarm` | `set_alarm`, `show_alarms` | `AlarmClock.ACTION_SET_ALARM` / `ACTION_SHOW_ALARMS` intents | `SET_ALARM` (normal, auto-granted) |
| `email` | `compose` | `Intent.ACTION_SENDTO` (`mailto:`) | none — composer only, no reading |
| `notifications` | post a notification (title/message) | `NotificationCompat` via shared `NotificationHelper` | `POST_NOTIFICATIONS` (runtime, Android 13+) |
| `step_counter` | `snapshot` (cumulative count + timestamp) | `SensorManager.TYPE_STEP_COUNTER` | `ACTIVITY_RECOGNITION` (runtime, Android 10+) |
| `work_manager` | `schedule_notification`, `schedule_periodic` (≥15 min), `cancel_work` | `WorkManager` + `NotificationWorker` | `POST_NOTIFICATIONS` for delivery |
| `heartbeat` | `schedule` (push/agent, one-time/periodic ≥15 min), `cancel`, `list`; agent-mode wakes `ReactLoopRunner` headless with a safe read-only tool subset (`HEARTBEAT_OK` = stay silent) → assistant message into the source chat + push (reuses `TurnNotifier` deep-link) | Room `heartbeats` registry + `HeartbeatWorker` (CoroutineWorker) | `POST_NOTIFICATIONS` |
| `database_query` | raw `sql` (SELECT/INSERT/UPDATE/DELETE) | Room/SQLite on `AppDatabase` | none — DDL blocked, multi-statement blocked, SELECT capped at 200 rows |
| `external_files` | `read_file`, `write_file`, `list_dir` | Storage Access Framework via `ContentResolver` | none — scoped to user-granted `content://` URIs only |
| `file_tool` | `read_file`, `write_file`, `list_dir`, `search_files` | `java.io.File`, sandboxed to `filesDir/agent` | none |
| `memory_tool` | `remember_fact`, `search_memory`, `recall_episodes` | `MemoryRepository` (Room) | none |
| `render_card` | render a Driven UI card | in-process (see Driven UI Engine) | none |

**Permission model.** Tools run on a background dispatcher and cannot themselves launch the runtime-permission dialog. The user grants permissions ahead of time in **Settings → ИНТЕГРАЦИИ**, and each tool calls the shared `Context.requirePermission(permission, humanName): ToolResult.Error?` helper in `agent/tools/PermissionUtils.kt`, returning a recoverable Russian "нет разрешения" error when a permission is missing.

Reading email (Gmail API/OAuth) is intentionally **not** implemented — `EmailTool` only opens a pre-filled draft. `READ_SMS` is restricted on the Play Store (requires default-SMS-handler status); this is fine for personal/sideload builds — a publishing constraint, not a development one.

---

## Code Style Guidelines

- **Kotlin code style:** `official` (set in `gradle.properties`).
- **Indent:** 2 spaces (observed in the existing source).
- **Trailing commas:** Generally used.
- **Imports:** Use explicit imports; no wildcard imports.
- **Compose conventions:**
  - Use `Modifier.testTag("...")` on interactive elements for UI testing.
  - Theming uses `MaterialTheme.colorScheme.*` and the custom palette in `Color.kt`.
  - Theme is forced dark (`darkTheme = true`); there is no real light palette — `LightColorScheme` is aliased to `DarkColorScheme`.
  - Headline/display typography uses a serif font (Lora); body/label use the system sans font.
- **Language mixing:** UI strings are in **Russian** by default, with runtime language switching (en/es/de/fr/uk) via `UserPreferencesRepository.language`. Class names and log tags are English, but comments/KDoc are inconsistently mixed — some files carry Russian explanatory comments alongside English `@trace` markers (see Traceability below). Don't assume comments will be English.

---

## Testing Strategy

The project has light but real coverage, concentrated on the newer agent/LLM plumbing:

1. **Unit tests** (`app/src/test/java`)
   - Legacy template sanity checks — physically under `com/example/` but package-declared `com.aiopenpocket` (see namespace gotcha above): `ExampleUnitTest.kt`, `ExampleRobolectricTest.kt`, `GreetingScreenshotTest.kt` (Roborazzi; renders an inline `Text("Hello Robolectric")`, doesn't exercise real app UI).
   - Real coverage, under `app/src/test/java/com/aiopenpocket/...` (directory matches package here):
     - `agent/core/llm/OpenAiSseParserTest.kt` — SSE stream parsing
     - `agent/core/llm/ProviderFallbackChainTest.kt` — AUTO ordering, NIM multi-model, user-model override
     - `agent/core/mcp/ToolExecutorTest.kt` — tool dispatch/error handling
     - `agent/core/prompt/ContextWindowBuilderTest.kt` — history head/tail/token trimming
     - `agent/core/profile/ExtractUserProfileUseCaseTest.kt` — onboarding profile extraction
     - `agent/loop/AgentModeTest.kt` — CHAT/PLAN/GOAL mode behavior
     - `ui/chat/DrivenUiEngineTest.kt` — card parsing

2. **Instrumented tests** (`app/src/androidTest/java`)
   - `ExampleInstrumentedTest.kt` — basic context/Activity sanity check only.

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

**Still untested:** `ChatViewModel` and the other ViewModels, `AgentLoopOrchestrator`/`ReactLoopRunner` themselves, the `agent/tools/*` implementations, and the repository layer (`ConversationRepository`/`MemoryRepository`/`SettingsRepository`). These would be the highest-value next additions — coverage has grown from "very low" but is still concentrated on the LLM/prompt plumbing rather than the orchestration or UI layers.

---

## Security Considerations

- **Bundled API keys** are injected via the Secrets Gradle Plugin from `.env` and become `BuildConfig` fields at compile time — not obfuscated in the APK; treat debug builds as containing plaintext secrets.
- **User-entered API keys** (per provider) and the selected provider/model are stored in `SettingsRepository`, backed by **`EncryptedSharedPreferences`** (AES256_GCM, KeyStore-backed master key) — encrypted at rest, unlike the plain SharedPreferences used in earlier versions of this app.
- **Conversations, messages, facts, and episodes** live in the local Room database (`queen_chat_db`, currently schema v9; `fallbackToDestructiveMigration()` is enabled, so schema bumps during development drop and recreate the DB). There is no more "session cookie" concept or `user_cookies` table — that, along with the old `ChatRepository`/`ChatDao`, has been fully removed.
- **Onboarding/UI preferences** (persona, language, roadmap progress, goal-mode iteration cap) live in an unencrypted AndroidX DataStore (`UserPreferencesRepository`) — not sensitive data.
- **`database_query` tool** gives the LLM raw SQL access to the app's own Room database (SELECT/INSERT/UPDATE/DELETE only — DDL, multi-statement queries, and result sets beyond 200 rows are blocked). It's scoped to the app's own DB, not system databases, but it's still an LLM-controlled SQL execution path worth keeping in mind when reasoning about prompt-injection risk from tool results or untrusted memory content.
- **Debug keystore** is committed to the repo as `debug.keystore.base64`. Do **not** use this for release builds.
- **Network security:** the app calls one of three chat-completions endpoints depending on the resolved provider — `integrate.api.nvidia.com` (NIM), `api.groq.com` (GROQ), `models.inference.ai.azure.com` (GitHub Models). No certificate pinning is implemented.
- **Voice services** (`AssistInteractionService`, `AssistSessionService`) are exported but protected by the `BIND_VOICE_INTERACTION` system permission; `RECORD_AUDIO` is a runtime permission gating both system voice assist and in-chat dictation.
- **ProGuard/R8:** `isMinifyEnabled = false` in both build types currently. No code shrinking or obfuscation is applied.

---

## Traceability

`traceability.yaml` at the repo root is a manually-curated, frozen-ID feature registry (title, rationale, `TZ.md` section reference). The `traxer` CLI (`traxer scan` / `check` / `doctor` / `context`) diffs it against `// @trace <feature-id>` markers scattered through the source to detect drift between documented features and code — that's a narrower, code-level version of the same staleness problem this document itself had. Use the `trace` skill when adding or finishing a feature to keep `traceability.yaml` and `@trace` markers in sync; don't strip `@trace` comments when refactoring a marked file.

---

## Key Files for Agents

| File | Purpose |
|------|---------|
| `app/build.gradle.kts` | Module build config, dependencies, signing, secrets plugin setup |
| `gradle/libs.versions.toml` | Version catalog for most dependencies (Ktor is pinned inline in `build.gradle.kts`, not here) |
| `.env` / `.env.example` | Bundled API key secrets (not committed) |
| `app/src/main/java/com/example/ui/chat/ChatViewModel.kt` | Chat state, intent handling; delegates each turn to `AgentLoopOrchestrator` |
| `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt` | Main per-turn entry point (CHAT/PLAN/GOAL modes) |
| `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt` | Shared ReAct loop engine used by every orchestrator |
| `app/src/main/java/com/example/agent/core/llm/OpenAiCompatClient.kt` | HTTP/SSE client for chat completions, provider fallback |
| `app/src/main/java/com/example/agent/core/agent/MiniAgent.kt` | Mini-agent roster data model + catalog |
| `app/src/main/java/com/example/agent/core/mcp/ToolRegistry.kt` | Tool lookup/schema resolution for the LLM |
| `app/src/main/java/com/example/di/AppGraph.kt` | Metro DI root graph |
| `app/src/main/java/com/example/ui/chat/DrivenUiEngine.kt` | JSON-to-Compose card renderer (19 templates) |
| `traceability.yaml` | Feature registry consumed by the `traxer` CLI |
| `debug.keystore.base64` | Base64-encoded debug keystore |

---

## Notes for AI Agents

- Do **not** assume Hilt/Dagger conventions. This project uses **Metro** (`dev.zacsweers.metro`). Use `@Inject`, `@DependencyGraph`, `@ContributesTo`, `@BindingContainer`, `@ContributesBinding`, and `@Provides @IntoSet` accordingly.
- **Package vs. directory mismatch:** every file's `package` statement already says `com.aiopenpocket.*`, but most files still physically live under `app/src/main/java/com/example/...`. Don't assume directory path encodes package — search by class/package name instead of by path. Place *new* files under `com/aiopenpocket/...` (matching their declared package), the way the `voice/` feature and every test file already do; don't add further to `com/example/`.
- The UI defaults to Russian but is multi-language (`UserPreferencesRepository.language`; en/es/de/fr/uk supported via `LocaleMapping`/`languageLabel`). If adding new user-visible strings, Russian is still the primary/expected language unless you're explicitly localizing.
- The app forces a dark theme (`Theme.kt` hardcodes `darkTheme = true`; there is no real light palette). Avoid adding light-theme-specific colors unless explicitly requested.
- The system prompt is **not** built inline in `ChatViewModel` anymore — it's assembled by `PrePromptAssembler` from `assets/prompts/system_base.md` plus mode/persona/skill/memory sections. When changing prompt behavior, look there first, and preserve the JSON/tool-call contract that `DrivenUiEngine` and `ToolRegistry` expect.
- When adding or finishing a feature, update `traceability.yaml` and any `@trace` markers on touched files (see Traceability above).
