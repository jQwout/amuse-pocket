package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb

import com.aiopenpocket.i18n.tr

object OnboardingContent {
  /**
   * Первый экран, который человек видит вообще. Пишем максимально просто и БЕЗ терминов: ни
   * «стриминга», ни «промпта», ни «препромпта», ни персон. Каждое из этих понятий разбирается
   * дальше по «Пути агента», где под него подведена почва (стриминг — урок «Токены и скорость»,
   * препромпт — «Как ИИ читает твой запрос»). Здесь термин только отпугнёт: человек может не знать
   * даже, что такое чат.
   *
   * Стриминг при этом никуда не делся — текст печатается по словам, и его ПЕРЕЖИВАЮТ, не называя.
   */
  val INTRO_MARKDOWN: String get() = tr(INTRO_MARKDOWN_RU, INTRO_MARKDOWN_EN)

  private const val INTRO_MARKDOWN_EN = """
Hi! 👋

This is **AI Open Pocket** — an agent in your pocket. An agent doesn't just answer — it also does things: writes a note, reminds you of a task, remembers something about you for later.

Talking to it is easy: write in plain words, the way you'd write to a person. It reads and replies. No special commands to learn.

Ask anything — even the simplest things. There are no silly questions here, and asking again as many times as you like is fine too.

Now let's set it up for you. It takes a minute.
"""

  private const val INTRO_MARKDOWN_RU = """
Привет! 👋

Это **AI Open Pocket** — карманный агент. Агент — значит не просто отвечает, а ещё и делает: запишет заметку, напомнит о деле, запомнит что-то о тебе на будущее.

Общаться с ним просто: пиши обычными словами, как написал бы человеку. Он прочитает и ответит. Никаких особых команд запоминать не нужно.

Спрашивать можно что угодно — даже самое простое. Глупых вопросов здесь не бывает, и переспрашивать сколько угодно раз тоже нормально.

Сейчас настроим его под тебя. Это займёт минуту.
"""
}
