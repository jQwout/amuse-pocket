# AI Open Pocket

An Android AI agent that teaches people how AI agents work — inside the agent itself. 31 short
lessons, each with an interactive scene you can touch; illustrations drawn by Muse Image; a lesson
where Muse Spark watches a video; and an agent builder at the end. The agent runs on Meta's Muse
models.

## Built on Meta models

- **Muse Spark** (`meta/muse-spark-1.3`) — the agent's brain: the tool loop, onboarding and every
  lesson question; it also watches short videos in the «Как модель видит» lesson.
- **Muse Image** (`meta/muse-image`) — illustrates the lessons (pre-generated and bundled) and
  redraws a lesson scene around the learner on request.

Both are reached through [OpenRouter](https://openrouter.ai). Only Meta models are used: the model
list and the fallback chain accept `meta/…` models only, and the `-contributor` variants (which the
vendor may train on) are filtered out.

## Run locally

Prerequisites: [Android Studio](https://developer.android.com/studio), an OpenRouter API key.

1. Open the project in Android Studio.
2. Create `.env` in the project root (see `.env.example`) and set `OPENROUTER_API_KEY` to your key.
   The key is baked into **debug** builds only; a release build refuses a live key.
   You can also enter the key later in the app: Settings → КЛЮЧ API (Ключ OpenRouter).
3. Run the `app` configuration on an emulator or a device (Android 7.0+).

## Stack

Kotlin, Jetpack Compose, Metro DI, Ktor (SSE streaming), Room, WorkManager, Coil.
