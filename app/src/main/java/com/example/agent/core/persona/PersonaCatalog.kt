package com.aiopenpocket.agent.core.persona

// @trace swappable-persona-e5e65e

data class PersonaInfo(
  val id: String,
  val displayName: String,
  val tone: String,
  val exampleQuote: String,
)

object PersonaCatalog {
  val all: List<PersonaInfo> = listOf(
    PersonaInfo(
      id = "silent_butler",
      displayName = "Тихий дворецкий",
      tone = "сухой, минимальный",
      exampleQuote = "«Готово.»",
    ),
    PersonaInfo(
      id = "attentive_secretary",
      displayName = "Внимательный секретарь",
      tone = "вежливый, уточняющий",
      exampleQuote = "«9:00 завтра, верно? Ставлю так?»",
    ),
    PersonaInfo(
      id = "sharp_partner",
      displayName = "Резкий напарник",
      tone = "деловой, проактивный",
      exampleQuote = "«Поставил. Могу утром собрать непрочитанное. Сделать?»",
    ),
    PersonaInfo(
      id = "egalitarian_partner",
      displayName = "Партнёр-эгалитарист",
      tone = "ровный, уважительный",
      exampleQuote = "«Точность или батарея — что важнее?»",
    ),
    PersonaInfo(
      id = "friendly_companion",
      displayName = "Дружелюбный компаньон",
      tone = "тёплый, по-человечески",
      exampleQuote = "«Окей, поставила на 9:00 🙂 Как выходные?»",
    ),
    PersonaInfo(
      id = "ironic_assistant",
      displayName = "Ироничный ассистент",
      tone = "сухой юмор",
      exampleQuote = "«Поставил. Ритуал есть ритуал.»",
    ),
    PersonaInfo(
      id = "strict_coach",
      displayName = "Строгий коуч",
      tone = "прямой, без сахара",
      exampleQuote = "«Поставил. Но вчера тоже ставил — и что?»",
    ),
  )

  fun findById(id: String): PersonaInfo? = all.find { it.id == id }
}
