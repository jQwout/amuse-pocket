package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

/**
 * Context of the current lesson step, injected into the system prompt of a tutor turn (a free question
 * to the LLM). Needed because card steps (reveal/concept/sort/…) are published with an empty `content`
 * and do NOT get into the prompt history — without it the tutor would answer without seeing what the
 * learner asked about. A pure string assembly (covered by LessonQuestionTest).
 */
object LessonQuestion {

  /** The context line for the system prompt, or "" if the step has no extractable text. */
  fun stepContext(lessonTitle: String, step: LessonStep?): String {
    val body = step?.let(::stepText).orEmpty().trim()
    if (body.isEmpty()) return ""
    val title = lessonTitle.trim()
    val where = if (title.isEmpty()) "на шаге урока" else "на шаге урока «$title»"
    return "Ученик сейчас $where. Содержание этого шага: $body"
  }

  /** The step's plain text by type — for cards it is assembled from their fields. */
  private fun stepText(step: LessonStep): String = when (step.type) {
    "text" -> step.text
    "reveal" -> "${step.question} — ${step.answer}"
    "concept" -> listOf("${step.term}: ${step.definition}", step.example).filter { it.isNotBlank() }.joinToString(". ")
    "example" -> (listOf(step.label) + step.parts.map { it.text }).filter { it.isNotBlank() }.joinToString(" · ")
    "fill" -> "${step.template} (пропущено: ${step.answer})"
    "sort" -> "${step.instruction} ${step.items.joinToString(", ")}"
    "match" -> "${step.instruction} " + step.pairs.joinToString("; ") { "${it.term} — ${it.def}" }
    "scenario" -> "${step.situation} ${step.q} Варианты: ${step.options.joinToString(", ")}"
    "image" -> step.caption.ifBlank { step.alt }
    "video", "segment" -> step.instruction
    "animation" -> step.caption
    "quiz" -> step.questions.joinToString("; ") { it.q }
    else -> step.text
  }
}
