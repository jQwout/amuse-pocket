# Authoring Guide for LLM Mini-Lessons

## Target Audience

Non-technical users encountering LLMs for the first time. Assume curiosity but no background in machine learning, programming, or mathematics. The reader uses chatbots but does not understand how they work under the hood.

## Tone and Voice

- Informal, friendly, direct address ("ты", не "вы").
- Conversational but precise — no filler, no marketing hype.
- Confident authority without condescension.
- Occasional colloquialisms are fine if they aid intuition (e.g., "шустрые", "рождается по одному токену").

## Content Architecture

### Opening (first 2-3 steps)

Start with a relatable observation or user pain point. Do not begin with definitions. Example:

> "Замечал, что длинный ответ от бота появляется не сразу, а словно печатается по словам?"

Then introduce the core concept through this observation.

### Body (middle steps)

Build understanding in layers:
1. **Intuition**: analogy or concrete scenario
2. **Mechanism**: what actually happens (simplified)
3. **Implications**: why this matters to the user

Cross-reference between steps. Each step should feel like a natural continuation, not a disconnected fact.

### Quiz Design (final step)

Questions must require connecting concepts, not just recalling definitions. Good vs bad:

- Bad: "Что такое токен?" — tests memorization of definition
- Good: "Почему длинный диалог с моделью замедляется?" — tests understanding of relationship between input tokens and processing

Distractor construction:
- Each wrong option should reflect a common misconception.
- Avoid obviously absurd answers (e.g., "потому что у неё хуже интернет" is acceptable as a joke distractor but should not be the pattern).
- Vary the correct answer position across questions.

## Topic Coverage Patterns

When creating a lesson on an LLM topic, cover these dimensions:

| Dimension | Question to answer |
|---|---|
| **What** | Core concept in simplest terms |
| **How** | Mechanism or process (intuitively) |
| **Why it matters** | User-relevant implications |
| **Trade-offs** | Speed vs quality, cost vs capability, etc. |
| **Misconception** | Common wrong belief to address |

Common LLM lesson topics:
- Tokens and context windows
- Model reasoning chains ("thinking")
- Temperature and randomness
- System prompts / instructions
- Hallucinations and factuality
- Fine-tuning vs prompt engineering
- Multimodal capabilities
- Speed differences between models

## Formatting

- Use em-dash (`—`) for inline explanations, not parentheses.
- Use arrows (`→`) for causal relationships sparingly.
- Avoid bullet points inside text steps — use flowing prose.
- Numbers: prefer natural language ("три четверти") over fractions for non-technical audiences.
