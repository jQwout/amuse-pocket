# AI Open Pocket

**Build your first AI agent on Meta's models, right on your phone — for everyone curious about
agents but too shy to ask.**

AI Open Pocket is an Android platform for building AI agents on Meta's Muse models, with learning
built in. Remember the "For Dummies" books of the 90s? They didn't lecture you about Windows — they
walked you through using it. This does the same for AI agents: you don't read about an agent, you
build one, and it does real things on your own phone.

There is no one to feel silly in front of — you ask the model, not a person. And there is nothing to
break: a dry run simulates every action, and anything that deletes data or leaves the phone waits
for your yes.

## What it does

- **Build.** A no-code agent builder: a role, instructions, on-device tools (reminders, alarms,
  calendar, notifications, files, memory, email drafts, spaced-repetition cards, UI cards in chat and
  more), skills, modes (chat, reflection, plan, goal) and rules. A dry run simulates every side effect
  before the agent goes live.
- **Run.** Agents live on the phone: a ReAct loop over function calling, layered memory, background
  reminders and heartbeats, and a structured plan engine with approval and evidence-checked steps.
  Reading and writing just happen; deleting or sending anything off the device always asks in chat
  and is never done in the background.
- **Learn.** 31 short lessons, from "what is an AI" to building your own agent. Each lesson is a strict
  step contract driven by code, with an interactive scene you can touch, and Meta's models work live
  inside some of them. The course is written in Russian; the lessons on the demo path are also in
  English.

## Built on Meta models

- **Muse Spark** — the brain of every agent: the tool loop, reflection, plans, onboarding and every
  lesson question. In the "How a model sees" lesson it also watches a short video and explains what it
  saw and what it is unsure about.
- **Muse Image** — illustrates the course (generated ahead of time and bundled, so lessons work
  offline) and redraws a lesson scene around the learner on request.
- **SAM 3.1** — in the same lesson you name one thing in a photo (yours or a bundled example) and SAM
  outlines every instance of it. It is available only on Meta Model API, so this step needs a Meta key;
  without one the lesson says so and lets you skip ahead.

There are two routes to the same models:

| Route | When | Model ids |
| --- | --- | --- |
| **Meta Model API** (`api.meta.ai`) — primary | a Meta key is set in Settings | `muse-spark-1.3`, `muse-spark-1.2`, `muse-spark-1.1`, `muse-wonder-1.0` |
| **OpenRouter** — backup | no Meta key | `meta/muse-spark-1.3`, … (Meta models only) |

The key decides the route; there is no switch. Settings show where requests go right now. Fallback
happens only between models of one route, never across routes. Model variants that the vendor trains
on (`-contributor`) are filtered out everywhere. The lesson video and live lesson images still go
through OpenRouter.

## Your key, your quota

- You type your key into the app (Settings → API key). It is stored in `EncryptedSharedPreferences`
  behind an Android Keystore master key.
- No key lives in this repository, and the Meta key is never compiled into a build.
- A Gradle check fails the release build when `.env` holds a live provider key: the secrets plugin
  copies `.env` into `BuildConfig`, and `BuildConfig` ships inside the AAB.
- Every model call — text, image, video — is metered in a weekly spend card. Meta reports tokens only,
  so the app prices Meta calls from Meta's price list.

## Build and run

Prerequisites: [Android Studio](https://developer.android.com/studio) with JDK 21, and an emulator or
a device on Android 7.0+.

1. Clone the repository and open it in Android Studio.
2. Run the `app` configuration.
3. In the app, open **Settings → API key** and paste your **Meta Model API** key. Get one at
   [dev.meta.ai](https://dev.meta.ai/docs/quickstart). The line under the keys should say that requests
   go straight to Meta Model API.
4. For English, open **Settings → Language → English**.

Optional: an OpenRouter key — in Settings, or in a local `.env` as `OPENROUTER_API_KEY` (see
`.env.example`; debug builds only). It is needed for the lesson video and for redrawing lesson images.

From the command line:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Project layout

All code is in the `app` module, under `app/src/main/java/com/aiopenpocket/`:

| Package | What lives there |
| --- | --- |
| `agent/loop` | One agent turn: the ReAct loop, reflection, plan orchestration, `TurnManager` (turns survive leaving the chat screen) |
| `agent/core/llm` | Streaming OpenAI-compatible client, the two routes, model catalogs, Meta pricing |
| `agent/core/mcp`, `agent/tools` | On-device tools: JSON schemas, validation, risk classes, the approval flow |
| `agent/core/plan` | The structured plan engine |
| `agent/core/agent`, `skill`, `hook`, `policy` | User-built agents, skills, hooks and execution rules |
| `agent/core/memory`, `profile`, `prompt` | Memory layers, user profile, prompt assembly |
| `agent/core/lesson` | Lessons as strict step contracts in chat |
| `agent/workers` | WorkManager: reminders and background heartbeats |
| `ui/` | Jetpack Compose screens: chat, agents and the builder, the course, settings |
| `i18n/` | Interface language: Russian and English side by side in one `tr(ru, en)` call |

Lessons are JSON in `app/src/main/assets/lessons/`; model catalogs are in
`app/src/main/assets/config/`.

## Traceability

`traceability.yaml` lists every feature with the reason it exists, and `@trace <id>` markers tie it to
the code. `traxer check` runs before each commit and flags features without code, broken links and
code that changed since its reason was last confirmed.

## Stack

Kotlin, Jetpack Compose, Metro DI, Ktor (SSE streaming), Room, WorkManager, Coil. Tests: JUnit,
Robolectric and Roborazzi screenshot tests; `.github/workflows/android.yml` builds the app and runs
them.

## How it was built

`orchestrator/` holds the working notes of the agent that coordinated the work: the acceptance criteria
(`CRITERIA.md`), the roadmap, and a journal of every cycle, including what was checked on a device and
what was not. `python tools/check.py` grades the project against those criteria in one command.

Before publication the history was rewritten once to drop an old build artifact. Commit hashes quoted
inside `orchestrator/` refer to the earlier private history; `orchestrator/COMMIT_MAP.txt` maps each of
them to its commit here.

## License

[MIT](LICENSE).
