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

Every model call goes to the **Meta Model API** (`api.meta.ai`) and nowhere else: `muse-spark-1.3`,
`muse-spark-1.2`, `muse-spark-1.1` and `muse-wonder-1.0`. Fallback happens only between those models.
Model variants that the vendor trains on (`-contributor`) are filtered out everywhere. Without a key
the app does not guess or fall back: every model action says "add your Meta API key" and points to
Settings.

### Following Meta's agent guidance

The agent loop was checked against the Meta Model API docs (tool calling, reasoning, prompt caching,
error handling, agent frameworks) and the Muse Code permission model:

- **Stable prompt prefix.** The system prompt runs from stable to volatile (base, language, role,
  skills, instructions, then plan, current time, memory), so Meta's automatic prefix cache survives
  from turn to turn.
- **Reasoning effort per mode.** Lesson questions run at `low`, chat at `medium`, planning and
  self-critique at `high`, instead of leaving the level to the model.
- **Every run ends with an answer.** At the step limit the model gets one tool-less call to say what is
  done and what is not.
- **Errors the model can act on.** A failed tool, a timeout or arguments that are not valid JSON go back
  to the model as a tool result that names the real problem; 429/5xx back off and honour `Retry-After`
  before falling back to another model.
- **Approval before side effects.** Destructive and external tools always wait for the person, hooks
  cannot remove that floor, background runs never take destructive or external actions, and a test
  run of a new agent simulates every side effect unless you switch it to live.
- **Least privilege in the builder.** A new agent starts with no tools; each risky tool needs explicit
  consent, and the Instructions step checks the prompt against Meta's prompting guide (role, format,
  length, tone).
- **The right model for the job.** A built agent can run on Muse Wonder (long multi-step work) or
  Muse Spark (faster and cheaper), as Meta's agent guide suggests; the choice holds on either route.

## Your key, your quota

- **Enter your own Meta Model API key in onboarding or in Settings. Nothing is bundled.** The key is
  stored in `EncryptedSharedPreferences` behind an Android Keystore master key and goes only to Meta.
- No key lives in this repository, and no key is compiled into any build, debug included: the build
  has no step that copies `.env` or anything else into `BuildConfig`. `tools/check.py` fails if one
  ever comes back, and scans the built APK for provider keys.
- Every model call is metered in a weekly spend card. Meta reports tokens only, so the app prices Meta
  calls from Meta's price list.

## Build and run

Prerequisites: [Android Studio](https://developer.android.com/studio) with JDK 21, and an emulator or
a device on Android 7.0+.

1. Clone the repository and open it in Android Studio.
2. Run the `app` configuration.
3. In the app, paste your **Meta Model API** key: onboarding asks for it right before the first step
   that calls a model (you can skip and add it later in **Settings → API key**). Get one at
   [dev.meta.ai](https://dev.meta.ai/docs/quickstart). The line under the key field in Settings should say
   that requests go straight to Meta Model API.
4. For English, open **Settings → Language → English**.

There is no `.env` to fill in: the build reads no key.

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
