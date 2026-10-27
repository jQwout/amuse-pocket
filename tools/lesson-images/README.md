# Lesson images

Draws lesson illustrations with Muse Image and puts them into the build, so a lesson shows its picture
offline, for free and on any provider.

## Running

First see what will be drawn and how much it costs:

    python tools/lesson-images/pregenerate.py --dry-run

Draw (needs an OpenRouter key and Pillow: `pip install pillow`). Replace `sk-or-...` with your own real
key.

PowerShell (Windows):

    $env:OPENROUTER_API_KEY = "sk-or-..."; python tools/lesson-images/pregenerate.py

bash / Git Bash:

    OPENROUTER_API_KEY=sk-or-... python tools/lesson-images/pregenerate.py

Only some lessons — add `--only rag plan_mode`.

## What it does

Takes the `{"type": "image"}` steps that have an `imagePrompt` but no `asset`/`imageKey`. For each one:

1. asks `meta/muse-image` for a picture from the prompt and the shared lesson style;
2. shrinks it to a WebP up to 1280 px wide and puts it into
   `app/src/main/assets/lessons/images/generated/<lesson>-<prompt hash>.webp`;
3. writes `"asset": "lessons/images/generated/…"` into the lesson step.

The lesson JSON is edited as text, not re-serialized: manual formatting and line breaks stay as they
were. That is why the prompt is printable ASCII without quotes or backslashes, which
`LessonAssetsValidationTest` checks.

The script does not touch steps that already have an `asset`. If a previous run drew a picture but did
not get to write it into the lesson, the file is reused without a new generation.

## How it connects to the app

For a step with an asset the app shows the picture from the build and draws nothing. Drawing on the
phone (`LessonImageGenerator`) remains for steps without an asset and works only when an OpenRouter key
is set, whichever route the chat itself uses.

The prompt style (`STYLE` in the script) must match `LESSON_IMAGE_STYLE` in `LessonImageGenerator.kt`
verbatim, otherwise the pictures from the build and from the phone drift apart in style. This is
checked by `LessonImageStyleSyncTest`.
