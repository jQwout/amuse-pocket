# Lesson JSON Format Specification

## Structure

```json
{
  "id": "snake_case_identifier",
  "title": "Lesson Title (concise, 2-4 words)",
  "description": "One-sentence summary of what the learner will understand.",
  "iconKey": "icon_name",
  "steps": [
    { "type": "text", "text": "Educational paragraph." },
    { "type": "image", "asset": "lessons/images/diagram.svg", "caption": "Optional caption." },
    { "type": "quiz", "questions": [
      {
        "q": "Question text?",
        "options": ["Option A", "Option B", "Option C"],
        "correct": 0,
        "explain": "Why the correct answer is right, briefly."
      }
    ]}
  ]
}
```

## Field Reference

| Field | Type | Required | Description |
|---|---|---|---|
| `id` | string | yes | Snake_case unique identifier. Short, semantic. |
| `title` | string | yes | 2-4 words, action-oriented or noun phrase. Russian language. |
| `description` | string | yes | One sentence, outcome-focused. Russian language. |
| `iconKey` | string | yes | Semantic icon name (e.g., `speed`, `brain`, `chat`, `lock`). |
| `steps` | array | yes | Ordered array of step objects. Minimum 1 text step before any quiz. |

## Step Types

### `type: "text"`

| Field | Type | Description |
|---|---|---|
| `type` | string | Always `"text"` |
| `text` | string | One paragraph of educational content. Russian language. Keep under 120 words. |

Rules for text steps:
- One idea per paragraph.
- Lead with a hook or connection to previous step.
- Use analogies and concrete examples (avoid abstract definitions).
- Define jargon inline on first use: `term — explanation`.
- Prefer "ты" (informal you) over impersonal constructions.

### `type: "image"`

Renders a picture or diagram (raster or SVG) as its own step. One step = one image.

| Field | Type | Description |
|---|---|---|
| `type` | string | Always `"image"` |
| `asset` | string | Path under `app/src/main/assets/` (e.g. `lessons/images/foo.svg`). Tried FIRST — works offline. |
| `imageKey` | string | Shorthand for `lessons/images/{imageKey}.svg`. Use instead of `asset` when the file is an SVG in the images folder. |
| `url` | string | Optional http(s) URL used as a FALLBACK when `asset`/`imageKey` is empty or fails to load. |
| `caption` | string | Optional caption shown under the image. Russian language. (`alt` is used as a caption fallback.) |

Rules for image steps:
- Provide `asset` for offline-first lessons; add `url` only as a network fallback.
- SVG is preferred for diagrams — it scales crisply and can be authored as inline text. SVG colors are rendered as-is (theme colors do NOT apply), so put light text on colored fills to stay legible on both light and dark themes.
- The learner taps "Дальше →" to advance past an image step (it is not interactive).

### `type: "quiz"`

| Field | Type | Description |
|---|---|---|
| `type` | string | Always `"quiz"` |
| `questions` | array | Array of question objects, typically 3 per quiz block. |

Question object fields:

| Field | Type | Description |
|---|---|---|
| `q` | string | Question text, ends with `?`. Tests understanding, not memorization. |
| `options` | array | Exactly 3 strings. Plausible distractors. Randomize correct position. |
| `correct` | integer | Zero-based index of the correct option. |
| `explain` | string | 1-2 sentence explanation. Reinforces the concept, does not just state the answer. |

### Interactive step types

These render as interactive cards. **Display** types (`reveal`, `concept`, `example`) advance with the "Дальше →" button. **Answer** types (`fill`, `sort`, `match`, `scenario`) advance the lesson once solved — like a passed quiz.

| Type | Fields | Behaviour |
|---|---|---|
| `reveal` | `question`, `answer` | Shows the question; a button reveals the answer. Good as a hook before explaining. |
| `concept` | `term`, `definition`, `example` | A definition card: term + one-line definition + short example. |
| `example` | `label`, `parts: [{ text, role, note }]` | A titled breakdown into labelled fragments (e.g. parts of a prompt). |
| `fill` | `template` (contains `___`), `answer`, `caseSensitive` (bool) | Fill-in-the-blank; the typed word is checked against `answer`. |
| `sort` | `instruction`, `items: [string]`, `correct: [int]` (target order as indices into `items`), `explain` | Reorder items (↑/↓) into the correct order. |
| `match` | `instruction`, `pairs: [{ term, def }]` | Tap a term, then a definition, to connect them. |
| `scenario` | `situation`, `q`, `options: [string]`, `correct` (int), `explain` | A single situational multiple-choice question. |

Notes:
- `correct` is an array for `sort` (order) but a single integer for `scenario` — match the type.
- Answer types have no "Дальше" button — the learner must solve them to continue (a `scenario` advances after any pick, showing the right option). Keep them solvable.
- `input` and `challenge` (free-text → live chat) are NOT supported yet; avoid them for now.

## Pedagogical Constraints

- Minimum 4 text steps before the first quiz.
- Maximum 12 text steps per lesson.
- Exactly one quiz block per lesson, placed at the end.
- Exactly 3 questions per quiz.
- Quiz questions must test comprehension, not rote recall.
- Distractor options must be plausible to someone with partial understanding.
- Each text step must build on the previous — cumulative narrative, not isolated facts.
