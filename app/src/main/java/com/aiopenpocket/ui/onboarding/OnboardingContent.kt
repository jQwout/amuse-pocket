package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb

import com.aiopenpocket.i18n.tr

object OnboardingContent {
  /**
   * The very first screen a person sees at all. Written as simply as possible and WITHOUT terms: no
   * "streaming", no "prompt", no "preprompt", no personas. Each of these concepts is covered later along the
   * "Agent path", where the ground is prepared for it (streaming — the "Tokens and speed" lesson, the preprompt
   * — "How AI reads your request"). Here a term would only scare people away: a person may not even know what
   * a chat is.
   *
   * Streaming has not gone anywhere, though — the text prints word by word, and people EXPERIENCE it without
   * it being named.
   *
   * It is PLAIN text, not markdown (it used to be called INTRO_MARKDOWN and carried **bold** around the app's
   * name). It streams into StreamingBubble, which draws a bare Text on purpose: the stream recomposes on every
   * character, and the markdown renderer is the heavy path that once left chat bubbles empty. The asterisks
   * showed up raw on the first screen a person sees; drawing markdown mid-stream would also flash a half-typed
   * "**Ope" until the closing pair arrives, so the markers are gone and must not come back.
   */
  val INTRO_TEXT: String get() = tr(INTRO_TEXT_RU, INTRO_TEXT_EN)

  private const val INTRO_TEXT_EN = """
Hi! 👋

This is AI Open Pocket — an agent in your pocket. An agent doesn't just answer — it also does things: writes a note, reminds you of a task, remembers something about you for later.

Talking to it is easy: write in plain words, the way you'd write to a person. It reads and replies. No special commands to learn.

Ask anything — even the simplest things. There are no silly questions here, and asking again as many times as you like is fine too.

Now let's set it up for you. It takes a minute.
"""

  private const val INTRO_TEXT_RU = """
Привет! 👋

Это AI Open Pocket — карманный агент. Агент — значит не просто отвечает, а ещё и делает: запишет заметку, напомнит о деле, запомнит что-то о тебе на будущее.

Общаться с ним просто: пиши обычными словами, как написал бы человеку. Он прочитает и ответит. Никаких особых команд запоминать не нужно.

Спрашивать можно что угодно — даже самое простое. Глупых вопросов здесь не бывает, и переспрашивать сколько угодно раз тоже нормально.

Сейчас настроим его под тебя. Это займёт минуту.
"""
}
