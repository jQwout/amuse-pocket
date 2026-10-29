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
    "дружелюб", "формальн", "неформальн", "вежлив", "тепл", "официальн",
    // No "строг" or "warm": "строго не делай" and a running coach's "warm-up" are not a tone.
    "tone", "friendly", "formal", "casual", "polite", "professional", "playful",
  )
  private val BRIEF = listOf("кратк", "коротк", "лаконичн", "short", "brief", "concise", "terse")

  // A number next to a unit: "до 3 предложений", "under 100 words", "в 2 абзаца".
  private val NUMBER_UNIT = Regex(
    "\\d+\\s*(слов|предложен|абзац|строк|символ|пункт|words?|sentences?|paragraphs?|lines?|characters?|bullets?|items?)",
  )

  // "тон" as a whole word only (with its case endings): as a stem it sits inside "автономно", "тонкий".
  private val TONE_WORD = Regex("(?<!\\p{L})тон(?:а|е|ом|у)?(?!\\p{L})")

  // Telling the model how to think / what it is internally. Meta recommends describing the task.
  // A bare "цепочк" or "temperature" is not on the list: "цепочка шагов" is a task, and a weather or
  // cooking agent talks about temperature for real — only the sampling knob with a number counts.
  private val META = listOf(
    "think step by step", "think step-by-step", "chain of thought", "chain-of-thought",
    "you are a language model", "you are an ai language model", "as a language model",
    "думай пошагово", "рассуждай пошагово", "цепочка рассуждений", "цепочку рассуждений",
    "цепочка мыслей", "цепочку мыслей", "ты языковая модель", "ты — языковая модель",
    "ты - языковая модель",
  )
  private val SAMPLING_KNOB = Regex(
    "(?<!\\p{L})(?:set (?:the )?temperature|temperature\\s*[=:]?\\s*[01](?:[.,]\\d+)?(?!\\d)|" +
      "температур\\p{L}*\\s*[=:]?\\s*[01](?:[.,]\\d+)?(?!\\d))",
  )

  /**
   * A key counts only where a word starts: plain substring matching ticked "format" inside "information",
   * "tone" inside "milestone" and the Russian stems inside longer unrelated words. The end stays open on
   * purpose — the Russian keys are stems that take endings.
   */
  private fun List<String>.toWordStartRegex() =
    Regex("(?<!\\p{L})(?:" + joinToString("|") { Regex.escape(it) } + ")")

  private val ROLE_RX = ROLE.toWordStartRegex()
  private val FORMAT_RX = FORMAT.toWordStartRegex()
  private val TONE_RX = TONE.toWordStartRegex()
  private val BRIEF_RX = BRIEF.toWordStartRegex()
  private val META_RX = META.toWordStartRegex()

  fun analyze(text: String): Coverage {
    val t = text.lowercase()
    return Coverage(
      role = ROLE_RX.containsMatchIn(t),
      format = FORMAT_RX.containsMatchIn(t),
      length = NUMBER_UNIT.containsMatchIn(t) || BRIEF_RX.containsMatchIn(t),
      tone = TONE_RX.containsMatchIn(t) || TONE_WORD.containsMatchIn(t),
      metaInstruction = META_RX.containsMatchIn(t) || SAMPLING_KNOB.containsMatchIn(t),
    )
  }
}
