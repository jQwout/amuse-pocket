# AI Open Pocket — Guide for AI Coding Agents

AI Open Pocket is an Android app that teaches how AI agents work from inside a working agent.
The learner works through 31 short lessons in the chat. Each lesson has an interactive scene,
and the course ends in a builder where the learner makes their own agent. The agent runs on
Meta models (Muse Spark for text and video, Muse Image for pictures), reached through the Meta Model API
with a key the learner enters themselves.

- Single Gradle module `:app`, Kotlin + Jetpack Compose, one Activity (`MainActivity`, Navigation Compose).
- Package `com.aiopenpocket`; directories match packages: `app/src/main/java/com/aiopenpocket/`
  (tests under `app/src/test/java/com/aiopenpocket/`). Application ID: `com.aistudio.aiopenpocket.hzwpqy`.
- `minSdk 24`, `targetSdk`/`compileSdk 36`, JVM target 21 (`app/build.gradle.kts`).
- Paths in this guide are relative to `app/src/main/java/com/aiopenpocket/` (code) or
  `app/src/main/` (`assets/…`), except those starting with `app/`, `tools/`, `gradle/` or `.github/`
  and the root files named explicitly.

## Product surface

**Lessons in chat.** 31 JSON lessons live in `app/src/main/assets/lessons/*.json`.
`agent/core/lesson/LessonChatController.kt` drives them as a strict contract: code decides the
step order, and one step produces exactly one assistant message that carries a card. The LLM does
not run the lesson flow. A side question asked during a lesson is a one-shot `TUTOR` turn (no
tools, one iteration).
- Step contract: `agent/core/lesson/Lesson.kt`. Step types are `text`, `language`, `persona`,
  `profile`, `quiz`, `image`, `video`, `segment`, `animation`, `reveal`, `concept`, `example`, `fill`, `sort`,
  `match` and `scenario`. The fields are flat, with no polymorphic JSON.
- Each lesson has exactly one `animation` step: an interactive Compose scene in
  `ui/chat/components/Scene*.kt`, dispatched by `ui/chat/components/LessonAnimationScenes.kt`. Valid
  scene ids are listed in `LESSON_ANIMATION_SCENES` (`agent/core/lesson/LessonAnimationScenes.kt`).
- Images: Muse Image draws them ahead of time with `tools/lesson-images/pregenerate.py`. The files are
  bundled in `assets/lessons/images/generated/` and referenced by the step's `asset` field. Steps
  without an asset are drawn on the device by `LessonImageGenerator`. «Нарисуй под меня» (“Draw it
  for me”) redraws a picture around the learner's profile; only the free-text "about me" field
  leaves the phone (`LessonImagePersonalization`).
- Video: `how_model_sees` has a `video` step. Muse Spark watches a bundled sample
  (`assets/lessons/videos/`) or a clip the learner records (`LessonChatController.postVideoAnswer` →
  `VideoUnderstandingClient`).
- Segment: `how_model_sees` also has a `segment` step. SAM 3.1 outlines the thing the learner names
  (one English noun phrase) in a photo: the bundled sample, the camera or the gallery
  (`LessonChatController.postSegmentation` → `SegmentationClient`). SAM answers in text, not JSON:
  `SegmentationParser` reads the boxes, and the app draws them over the photo (`lesson_segment_result`
  card). It needs a Meta Model API key from Settings; without one the card says so and the step is
  skipped with «Next». Masks are parsed but kept raw and not decoded.
- Learning path: `ui/roadmap/RoadmapScreen.kt`, levels in `ui/roadmap/RoadmapLevels.kt`.
  `ROADMAP_LEVELS` has 30 levels, starting with the onboarding lesson `l1_welcome`. Library:
  `ui/roadmap/LibraryViewModel.kt` (`LIBRARY_LESSON_IDS`).
  The final lesson, `agent_blueprint`, ends with a button that opens the agent builder.
- Desktop proofreading tool for lesson JSON: `./gradlew -p tools/lesson-viewer run`.

**Agent.** The app has a general chat agent and 6 preset mini-agents (`agent/core/agent/MiniAgent.kt`:
budget, languages, tasks, reminders, nutrition, diary). It also has user-built agents
(`ui/agents/builder/`), structured plans, reminders, a memory graph and a scheduled-tasks screen.
Persona prompts are in `assets/personas/*.md` (7 personas), listed in `agent/core/persona/PersonaCatalog.kt`.

## Models and network

- **One route: the Meta Model API.** `LlmProvider` has a single value, `META` (`api.meta.ai/v1`).
  OpenRouter was removed on 30 Oct 2026 (the hackathon's first screening wants the Meta Model API).
  There is no cross-provider fallback — the chain only ever resolves the provider it was asked for.
  `SelectedLlmProvider` stays as a seam that returns `META`.
- **The key is the person's.** No key is bundled into any build, debug included. The learner enters a
  Meta key in onboarding (the key step, before the first model call, skippable) or Settings; both save
  it the same way — `SettingsRepository.setApiKey("META", key.trim())`, `EncryptedSharedPreferences`.
  Do not reintroduce a bundled key, the Secrets Gradle Plugin or a key-like `buildConfigField`:
  `tools/check.py` (T4, T4a) fails on them.
- **No key.** `ModelKeyGate` ("has the person entered a key?") is applied where a model call starts:
  `TurnManager.runTurn` (chat, lessons, plan/goal, the builder's test run) fails the turn with
  `missingKeyMessage()` and `TurnStatus.Failed.needsKey`, which the chat and the builder turn into an
  "Open Settings" button; `HeartbeatRunner` skips the wakeup and records the reason, with no push.
  `OpenAiCompatClient` keeps a last-resort `blank_key` check. Never let a keyless request reach Meta
  to come back as a raw HTTP 401.
- **Catalog.** The model list is static: `assets/config/models_meta.json`, loaded by
  `ModelCatalogLoader.loadMeta()`. `ModelCatalog.kt` has `trainsOnUserData`, which rejects
  `-contributor` variants. Models without tool support are dropped.
- **Fallback.** `ProviderFallbackChain.resolve` puts the model picked in Settings first and the rest
  in priority order, with at most 5 attempts (`MAX_ATTEMPTS`). It falls back between Meta models only.
  `OpenAiCompatClient` streams SSE over Ktor. It moves to the next model only if no answer content
  has been emitted yet. On 401/402/403 (a problem with the key) it stops the chain.
- **Other clients.** All three run on the person's Meta key and say "add your Meta key" without one.
  `ImageGenClient` calls Muse Image (`POST https://api.meta.ai/v1/images/generations`, model
  `muse-image-1.0`; the response has no media type, it comes from `output_format`; billed a flat $0.01
  per successful image). `VideoUnderstandingClient` calls Muse Spark with a base64 `video_url` part
  (`POST /v1/chat/completions`, MP4 only, `max_completion_tokens`; priced from the usage tokens with
  `MetaPricing`). `SegmentationClient` calls SAM 3.1 (`POST https://api.meta.ai/v1/responses`, model
  `sam-3.1`, billed per picture). The image and video request shapes are written from dev.meta.ai
  and have NOT been seen against a live call. They share `RetryBackoff.send` for 429/503. None of them
  falls back to another model, and only SAM streams (the client assembles the stream itself). Token and cost usage per call goes to Room through `LlmUsageRecorder`; Settings shows
  the weekly total.
- **API key.** Entered in the app (see "The key is the person's"); stored with `EncryptedSharedPreferences`
  (`data/repository/SettingsRepository.kt`), which also deletes the obsolete NVIDIA and OpenRouter keys
  from an updated device. There is no `.env` the build reads (`.env.example` is only a note).

## Agent architecture (`agent/`)

**Turns (`agent/loop/`).** `TurnManager` is an app-scoped singleton with one `TurnSession` per
conversation, so a turn keeps running after the user leaves the chat screen. `TurnForegroundService`
(a `shortService`, started through `AndroidTurnLifeSupport`) keeps the process alive during a turn.
`di/TurnModule.kt` routes each turn by `AgentMode`:
- `CHAT`, `TUTOR` → `AgentLoopOrchestrator`
- `REFLECT` → `ReflectionOrchestrator` (draft → critique → revise)
- `PLAN`, `GOAL` → `StructuredPlanOrchestrator`

All of them share `ReactLoopRunner`, the ReAct engine (`MAX_ITERATIONS = 10`). The same engine runs
headless heartbeat turns.

**Tools (`agent/tools/`).** There are 14 tools, each registered with `@Provides @IntoSet` in
`di/AgentToolsModule.kt`. `load_skill` is a 15th, registered in `di/SkillModule.kt`. Their user-facing
Russian names are in `agent/core/mcp/CapabilityNames.kt`. A preset or user agent sees only its own
`toolNames`. Risk is classified per call with `Tool.riskFor(arguments)` (`RiskClass`: READ / WRITE /
DESTRUCTIVE / EXTERNAL):

| Tool | Notes | Risk |
|---|---|---|
| `memory_tool` | facts and episodes (Room), namespaced per agent | `remember_fact` WRITE, else READ |
| `storage_tool` | per-agent key-value JSON store | `put` WRITE, `delete` DESTRUCTIVE, else READ |
| `file_tool` | sandbox `filesDir/agent` (`filesDir/agents/{id}` for a bound agent) | `write_file` WRITE, else READ |
| `external_files` | Storage Access Framework, user-granted URIs only | `write_file` DESTRUCTIVE (overwrites the user's document), else READ |
| `database_query` | SELECT-only over an allowlist of tables | READ |
| `render_card` | Driven UI card (`ui/chat/DrivenUiEngine.kt`), allowlisted per agent | READ |
| `notifications` | immediate notification | WRITE |
| `heartbeat` | push/agent reminders (see below) | `schedule` WRITE, `cancel` DESTRUCTIVE |
| `work_manager` | scheduled notifications | `cancel_work` DESTRUCTIVE, else WRITE |
| `calendar` | `CalendarContract` | list READ, create WRITE, delete DESTRUCTIVE |
| `alarm` | system alarm-clock intents | WRITE |
| `email` | opens a `mailto:` draft, never sends | EXTERNAL |
| `srs_tool` | spaced-repetition vocabulary (`agent/core/srs/`) | `add_word`/`review` WRITE, else READ |
| `step_counter` | step-sensor snapshot | READ |
| `load_skill` | pulls a skill's full instructions on demand | READ |

Tools cannot open runtime-permission dialogs. They check permissions through
`agent/tools/PermissionUtils.kt`, and the user grants them beforehand in Settings → «ИНТЕГРАЦИИ».

**Policy, approval, hooks (`agent/core/policy/`, `agent/core/hook/`).**
`ExecutionPolicy.evaluate` decides once per call:
1. A hook's `DENY` always wins, then a hook's `REQUIRE_APPROVAL`.
2. Otherwise, in `LIVE` mode READ and WRITE calls run, and DESTRUCTIVE and EXTERNAL calls **always
   wait for the user to approve them**. This holds in plain chat with no agent and no hooks; the
   floor cannot be configured away.
3. In `PREVIEW` mode (the builder's test run) only READ calls run. Everything else is simulated and
   reported to the model as "not executed", even after approval.

`ToolApprovalCoordinator` suspends the turn while the chat shows the request. No answer within
2 minutes counts as a denial. A background run (heartbeat, plan step) cannot show a prompt, so a
call that needs approval is denied at once.
`HookEngine` evaluates declarative per-agent rules (`AgentHook`, stored in Room) on turn, tool and
plan events. The actions are `ADD_INSTRUCTION`, `REQUIRE_APPROVAL`, `ALLOW`, `DENY`, `LOG` and `STOP`.
Hooks never run user code and never call the LLM. `DENY`, `REQUIRE_APPROVAL` and `STOP` keep firing
even after the per-run cap of 50 hook firings is spent.

**Prompt.** `agent/core/prompt/PrePromptAssembler.kt` builds the system prompt in this order:
1. `assets/prompts/system_base.md`, plus `tutorial.md` for tutorial chats
2. «Сейчас» (current local time and time zone)
3. language
4. persona *or* the bound agent's role
5. skill catalog
6. active plan summary
7. memory data, wrapped in an untrusted `<user_data>` fence (`UntrustedFence`)
8. instructions

`ContextWindowBuilder` then adds a trimmed history window. Tools that return external content set
`returnsUntrustedContent`, and their output is fenced before the model sees it.

**Memory (`agent/core/memory/`).** All in Room: conversations, messages, tags, facts, episodes and
heartbeats, with DAOs, entities and repositories (`ConversationRepository`, `MemoryRepository`).
A bound agent keeps its facts under its own namespace (`agent:{id}:`, `AgentMemoryPolicy`).
`ConversationAnnotator` derives titles, summaries and tags in the background.

**Heartbeats.** `heartbeat` schedules a static push or a headless agent turn, one-time or periodic
(at least 15 minutes, a WorkManager limit). The path is `HeartbeatWorker` →
`agent/workers/HeartbeatRunner.kt`. An agent heartbeat gets the agent's tools intersected with
`SAFE_TOOL_NAMES`. If the agent answers `HEARTBEAT_OK`, nothing is posted.
`agent/core/plan/schedule/HeartbeatScheduler.kt` arms the WorkManager jobs. `HeartbeatTool` and
`PlanScheduler` both use it.

**Plan engine (`agent/core/plan/`) is frozen: fix bugs, don't add features.**
- PLAN runs one LLM turn that sees only three internal control schemas (`submit_plan`,
  `propose_plan_revision`, `request_plan_information`) and never a real tool. `PlanValidator`
  checks the proposal, and it is stored as `AWAITING_APPROVAL`.
- The user approves it through `PlanCommands.approve`.
- GOAL executes the approved version step by step. A step may use only tools that are both the
  agent's and listed in the step's `expectedTools`.
- `PlanVerifier` completes a step only on persisted `PlanEvidence`.
- Long-running plans advance through plan-step heartbeats.
- `PlanRunner` first runs `PlanRecovery` on steps left mid-flight.
- Domain safety templates: `assets/plan_templates/`. Planner, executor and verifier prompts:
  `assets/prompts/plan/`.

**Agents, builder, skills.**
- `AgentResolver` merges the read-only presets (`MiniAgentCatalog`) and user agents from Room
  (`UserAgentRepository`) into immutable `AgentDefinition` snapshots.
- The builder (`ui/agents/builder/`) is a six-step wizard: identity, instructions, capabilities,
  loop, hooks, test & publish. Risky tools require consent. The test run defaults to `PREVIEW`.
- Skills (`agent/core/skill/`) use progressive disclosure. The prompt lists only manifests, and
  `load_skill` fetches the full text. Built-in skills are budget, diary, fitness and languages
  (`assets/skills/{id}/SKILL.md`). User skills come from Room (`UserSkillRepository`). If ids
  collide, the built-in skill wins.

## Stack

| Area | Library (version from `gradle/libs.versions.toml` / `app/build.gradle.kts`) |
|---|---|
| Language / build | Kotlin 2.3.0, AGP 8.9.1, KSP, Gradle Kotlin DSL, version catalog |
| UI | Jetpack Compose (BOM 2026.05.00), Material 3, Navigation Compose, glass components in `ui/components/glass/` |
| DI | Metro 1.1.1 (`dev.zacsweers.metro`) + metrox-viewmodel — not Hilt/Dagger |
| Network | Ktor client 2.3.11 (Android engine, SSE), kotlinx-serialization-json 1.6.3 |
| Storage | Room 2.7.0, DataStore Preferences, EncryptedSharedPreferences (security-crypto) |
| Background | WorkManager 2.10.0 |
| Images / text | Coil 2.7.0 (+ SVG), mikepenz multiplatform-markdown-renderer 0.41.0 |
| Tests | JUnit 4, Robolectric 4.16.1, Compose UI test, kotlinx-coroutines-test, Ktor MockEngine, work-testing, Roborazzi 1.59.0 |

Metro DI: the root graph is `di/AppGraph.kt` (`@DependencyGraph(AppScope::class)`). Modules are
`@ContributesTo(AppScope::class) @BindingContainer` objects in `di/`. Singletons use
`@Inject @SingleIn(AppScope::class)`. ViewModels use `@ContributesIntoMap` + `@ViewModelKey` and
are obtained in Compose with `metroViewModel()`.

## Build and test

JDK 21 is required. Set `org.gradle.java.home` in your user-level `~/.gradle/gradle.properties`,
never in the repo's own `gradle.properties`.

```bash
./gradlew :app:assembleDebug                      # debug APK
./gradlew :app:compileDebugKotlin                 # fastest correctness check
./gradlew :app:testDebugUnitTest                  # ~1000 JVM tests (Robolectric included); slow
./gradlew :app:testDebugUnitTest --tests '*ProviderFallbackChainTest'   # targeted run
./gradlew :app:recordRoborazziDebug               # re-record screenshot baselines
./gradlew :app:verifyRoborazziDebug               # compare against baselines
./gradlew :app:connectedDebugAndroidTest          # device test (one sanity test only)
```

- Roborazzi baselines are committed in `app/src/test/screenshots/`; each test sets its own `filePath`.
  CI (`.github/workflows/android.yml`) runs `assembleDebug`, then `testDebugUnitTest -Proborazzi.test.verify=true`.
- `LessonAssetsValidationTest` lints every lesson JSON and its images and scenes. Lesson assets are
  declared as test inputs, so editing a lesson re-runs it. `LessonImageStyleSyncTest` keeps the
  `STYLE` string in `pregenerate.py` identical to `LESSON_IMAGE_STYLE` in Kotlin.
- Signing: debug uses `debug.keystore` if it is present, otherwise AGP's default key. Release reads
  `KEYSTORE_PATH`, `STORE_PASSWORD` and `KEY_PASSWORD`. R8 is off (`isMinifyEnabled = false`).

## Traceability (traxer)

`traceability.yaml` is a hand-edited registry of features. Each entry has a frozen `id`, a
`title`, and a `rationale` that explains **why** the feature exists. Code links to it with
`// @trace <id>` markers (`.traceignore` excludes docs). The workflow is in `CLAUDE.md` and the
`trace` skill. In short:
- Run `traxer check` before committing.
- For a new feature, run `traxer id`, add a registry entry and a `@trace` marker.
- Add a `Trace: <id>` trailer to the commit.
- Run `traxer bless <id>` only after confirming the rationale still holds, and only after
  `git add`-ing new files.

Never strip `@trace` markers while refactoring.

## Conventions

- **UI language is Russian and English.** Onboarding lets the user pick, the option works, and English
  is the path the hackathon judges walk. Every user-visible string goes through `tr(ru, en)`
  (`i18n/AppLanguage.kt`): a bare literal on a screen reaches an English reader in Russian, which is
  how a whole screen stayed Russian under a green check until 27 Oct 2026. Russian addresses the user
  informally as «ты» — keep that voice, and write the English side as English, not as a calque of it.
- **Comments explain why**, not what. Most are in Russian; some KDoc is in English. Edit files with
  Russian text using an editor or tool that preserves UTF-8, not ad-hoc `sed`/`perl` byte edits.
- **Database, pre-release.** `AppDatabase` is schema version 11 with 18 entities. `DatabaseBindings`
  uses `fallbackToDestructiveMigration(dropAllTables = true)`. Bump the version on schema changes and
  write no `Migration` classes before the first release. Schemas are exported to `app/schemas/` and committed.
- **Safety lives in code, not prompts.** Classify new tools with `riskFor` and gate them through
  `ExecutionPolicy`. Mark tools that return external text with `returnsUntrustedContent`. Add new
  tool names to `CapabilityNames` and `AgentValidator.KNOWN_TOOL_NAMES`.
- **Theme:** light, dark or system (`data/repository/ThemeRepository.kt`). Use theme tokens
  (`ui/theme/`), not hard-coded colors.
- The system prompt is built only by `PrePromptAssembler`. Change prompt behavior there or in
  `assets/prompts/`, never inline in a ViewModel.

## Key files

| Path | Purpose |
|---|---|
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | Build, dependencies (no key reaches BuildConfig) |
| `di/AppGraph.kt`, `di/TurnModule.kt` | DI root; turn dispatch by mode |
| `agent/loop/TurnManager.kt`, `agent/loop/ReactLoopRunner.kt` | App-scoped turns; shared ReAct loop and policy gate |
| `agent/core/llm/OpenAiCompatClient.kt` | SSE chat client with model fallback |
| `agent/core/llm/ModelCatalog.kt`, `agent/core/llm/ProviderFallbackChain.kt` | Meta-only catalog, fallback order |
| `agent/core/policy/ExecutionPolicy.kt` | LIVE/PREVIEW, approval floor for DESTRUCTIVE/EXTERNAL |
| `agent/core/prompt/PrePromptAssembler.kt`, `assets/prompts/system_base.md` | System prompt |
| `di/AgentToolsModule.kt`, `agent/core/mcp/CapabilityNames.kt` | Tool registration, human names |
| `agent/core/lesson/LessonChatController.kt`, `agent/core/lesson/Lesson.kt` | Lesson driver, step contract |
| `ui/chat/ChatViewModel.kt`, `ui/chat/DrivenUiEngine.kt` | Chat state, card rendering |
| `ui/roadmap/RoadmapScreen.kt`, `ui/roadmap/RoadmapLevels.kt`, `ui/roadmap/LibraryViewModel.kt` | Learning path, library |
| `agent/core/agent/MiniAgent.kt`, `agent/core/agent/AgentResolver.kt` | Presets, agent resolution |
| `data/database/AppDatabase.kt`, `di/DatabaseBindings.kt` | Room schema and builder |
| `tools/lesson-images/pregenerate.py` | Pre-generates lesson images with Muse Image |
| `traceability.yaml`, `.env.example` (repo root) | traxer feature registry; API key template |
