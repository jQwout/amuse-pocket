package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

/**
 * Advice for the builder's Instructions step, based on Meta's prompting guide ("System prompts"):
 * name the role or task, state output format, length and tone up front, and don't explain the
 * model's internals to it.
 *
 * Deliberately a cheap keyword heuristic (ru + en), not NLP and not validation: a false "unchecked"
 * costs the user a glance at a checklist, whereas blocking a save on a guess would cost a good
 * agent. Nothing here may ever feed [AgentValidator].
 */
object InstructionsGuide {

  data class Coverage(
    val role: Boolean,
    val format: Boolean,
    val length: Boolean,
    val tone: Boolean,
    val metaInstruction: Boolean,
  )

  private val ROLE = listOf(
    "ты —", "ты -", "ты – ", "ты ассистент", "ты помощник", "твоя задача", "твоя роль", "твоя работа",
    "you are", "your job", "your task", "your role", "act as",
  )
  private val FORMAT = listOf(
    "списк", "таблиц", "формат", "маркированн", "пункт", "json", "markdown", "абзац",
    // Not a bare "list"/"table": they hide in "listen" and "suitable".
    "a list", "as list", "list of", "bullet", "a table", "format", "paragraph", "step-by-step",
  )
  private val TONE = listOf(
    "тон", "дружелюб", "формальн", "неформальн", "вежлив", "тепл", "официальн",
    // No "строг" or "warm": "строго не делай" and a running coach's "warm-up" are not a tone.
    "tone", "friendly", "formal", "casual", "polite", "professional", "playful",
  )
  private val BRIEF = listOf("кратк", "коротк", "лаконичн", "short", "brief", "concise", "terse")

  // A number next to a unit: "до 3 предложений", "under 100 words", "в 2 абзаца".
  private val NUMBER_UNIT = Regex(
    "\\d+\\s*(слов|предложен|абзац|строк|символ|пункт|words?|sentences?|paragraphs?|lines?|characters?|bullets?|items?)",
  )

  // Telling the model how to think / what it is internally. Meta recommends describing the task.
  private val META = listOf(
    "think step by step", "think step-by-step", "chain of thought", "chain-of-thought",
    "you are a language model", "you are an ai language model", "as a language model", "temperature",
    "думай пошагово", "рассуждай пошагово", "цепочк", "ты языковая модель", "ты — языковая модель",
    "ты - языковая модель", "температур",
  )

  fun analyze(text: String): Coverage {
    val t = text.lowercase()
    fun any(keys: List<String>) = keys.any { it in t }
    return Coverage(
      role = any(ROLE),
      format = any(FORMAT),
      length = NUMBER_UNIT.containsMatchIn(t) || any(BRIEF),
      tone = any(TONE),
      metaInstruction = any(META),
    )
  }
}
