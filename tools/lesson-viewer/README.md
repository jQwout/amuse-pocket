# Lesson viewer

Read the lesson content from `app/src/main/assets/lessons/` on the desktop, without building the app.

## Running

    ./gradlew -p tools/lesson-viewer run

Another lessons directory:

    ./gradlew -p tools/lesson-viewer run -Dlessons.dir=<path>

Edit a lesson's JSON → the «Перечитать» (reload) button in the window. No rebuild needed.

## What it shows

On the left, the list of lessons in the order of the learning path (as in the app), followed by the
lessons outside the path; on the right, the whole lesson in one scroll: every step as a card with a
`#number · type` label. Steps that have a correct answer (quiz, scenario, sort, match, fill) are shown
**with the answer filled in** — this is a reader for proofreading, not a trainer.

What the app silently turns into nothing is drawn here as a red plate: an unknown step type, a broken
`correct`, a missing image, a lesson that does not parse.

## What is not here

This is a CONTENT reader, not a preview of the look: no glass, no real cards from the app, no
interactivity, no LLM, no progress. Seeing how a step looks on the phone needs a module shared with
`:app` — a separate conversation.

## How it is built

A standalone Gradle build: it is not in the root `settings.gradle.kts`, it does not depend on `:app`
and is not part of traxer. Android Studio does not index it.

The lesson models are duplicated on purpose — a mismatch with the app is caught by
`AllLessonsParseTest` (it fails if a lesson gets a step type the viewer does not know).

Tests:

    ./gradlew -p tools/lesson-viewer test

- `AllLessonsParseTest` — all lessons parse, all step types are known
- `LessonAnswersTest` — parsing of the correct answers, including broken keys
- `LessonImagesLoadTest` — all lesson images exist and parse
- `RoadmapOrderTest` — the lesson order matches `ROADMAP_LEVELS` in the app

Design: `docs/superpowers/specs/2026-07-25-lesson-viewer-design.md`
Plan: `docs/superpowers/plans/2026-07-25-lesson-viewer.md`
