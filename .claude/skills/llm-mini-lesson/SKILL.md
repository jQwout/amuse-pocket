---
name: llm-mini-lesson
description: Create educational mini-lessons about LLMs (Large Language Models) as structured JSON. Use when the user asks to compose, write, create, or generate a lesson, tutorial, explainer, or educational content about how LLMs work — including topics like tokens, context windows, model reasoning, temperature, system prompts, hallucinations, fine-tuning, inference speed, or any concept related to AI language models. Also use when the user provides a lesson topic or outline and wants it turned into a structured mini-lesson. Output is always a JSON object following the lesson schema.
---

# LLM Mini-Lesson Creator

Compose educational mini-lessons about LLM concepts for non-technical audiences. Output is a single JSON object following the lesson schema.

## Workflow

1. Identify the lesson topic and target concept from the user's request
2. Read the authoring guide: `references/authoring-guide.md`
3. Read the format specification: `references/lesson-format.md`
4. Draft the lesson — follow all pedagogical constraints from the format spec
5. Validate JSON structure against the schema
6. Output the final JSON

## Output Rules

- Always output a single valid JSON object, not markdown code blocks.
- Lesson language: Russian (all text fields).
- Minimum 4 text steps, maximum 12 text steps.
- Exactly one quiz block at the end with exactly 3 questions.
- Each text step: one paragraph, under 120 words, one idea per step.
- Quiz questions: test comprehension, not memorization. Plausible distractors.
- Steps must form a cumulative narrative — each builds on the previous.

## Icon Key Selection

Pick a semantic icon key matching the lesson topic. Common choices:

| Topic area | iconKey |
|---|---|
| Speed, latency, tokens | `speed` |
| Memory, context, history | `memory` |
| Reasoning, thinking chains | `brain` |
| Accuracy, facts, truth | `target` |
| Prompting, instructions | `chat` |
| Security, privacy | `lock` |
| Creativity, generation | `sparkle` |
| Models, architecture | `cpu` |
| Cost, pricing | `wallet` |
| General introduction | `book` |

Choose the closest match. The iconKey should be a single lowercase word.

## Example Output

```json
{
  "id": "what_is_context",
  "title": "Что такое контекст",
  "description": "Почему модель не помнит разговор и как работает контекстное окно.",
  "iconKey": "memory",
  "steps": [
    { "type": "text", "text": "..." }
  ]
}
```

For a full example with all step types and quiz structure, see `references/lesson-format.md`.
