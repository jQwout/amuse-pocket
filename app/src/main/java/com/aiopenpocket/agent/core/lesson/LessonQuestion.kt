package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

/**
 * Контекст текущего шага урока для инъекции в системный промпт tutor-хода (свободный вопрос к LLM).
 * Нужен, потому что карточные шаги (reveal/concept/sort/…) публикуются с пустым `content` и НЕ
 * попадают в промптовую историю — без этого репетитор отвечал бы, не видя, о чём спросил ученик.
 * Чистая склейка строки (покрыта LessonQuestionTest).
 */
object LessonQuestion {

  /** Строка-контекст для системного промпта, либо "" если у шага нет извлекаемого текста. */
  fun stepContext(lessonTitle: String, step: LessonStep?): String {
    val body = step?.let(::stepText).orEmpty().trim()
    if (body.isEmpty()) return ""
    val title = lessonTitle.trim()
    val where = if (title.isEmpty()) "на шаге урока" else "на шаге урока «$title»"
    return "Ученик сейчас $where. Содержание этого шага: $body"
  }

  /** Плоский текст шага по типам — для карточек собираем из их полей. */
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
    "video" -> step.instruction
    "quiz" -> step.questions.joinToString("; ") { it.q }
    else -> step.text
  }
}
