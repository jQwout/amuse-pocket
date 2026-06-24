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

## Pedagogical Constraints

- Minimum 4 text steps before the first quiz.
- Maximum 12 text steps per lesson.
- Exactly one quiz block per lesson, placed at the end.
- Exactly 3 questions per quiz.
- Quiz questions must test comprehension, not rote recall.
- Distractor options must be plausible to someone with partial understanding.
- Each text step must build on the previous — cumulative narrative, not isolated facts.
