package com.aiopenpocket.agent.core.prompt

// @trace user-agent-builder-848153

/**
 * A tag envelope around untrusted data — and, which is all that matters here, neutralizing such tags
 * INSIDE the data itself.
 *
 * The prompt separates data from instructions with a tag (`<user_data>`, `<tool_output>`) and tells the
 * model that what is inside is data, not commands. That holds exactly until the content itself contains
 * the closing tag: the text after it is read as part of the trusted zone.
 *
 * The content comes from outside (files, the calendar) and — more importantly — gets LAUNDERED through
 * memory: the model read untrusted text and saved it with `remember_fact`, after which it is put into
 * the system prompt as a "user fact".
 *
 * Neutralization used to live only in [com.aiopenpocket.agent.loop.ReactLoopRunner] for `<tool_output>`;
 * `<user_data>` in [PrePromptAssembler] and in
 * [com.aiopenpocket.agent.core.plan.planner.PlanPromptBuilder] was inserted raw. That is exactly why a
 * shared point is needed: there is one invariant, several places that must keep it, and a mismatch
 * between them is the hole.
 */
object UntrustedFence {

  /**
   * [raw] with occurrences of `<tag`/`</tag` neutralized: they become `<_tag`/`</_tag`, so the envelope
   * cannot be closed or reopened from inside. Case does not matter — the model would understand
   * `</USER_DATA>` just as well.
   *
   * The text AROUND the tag is kept: the goal is to disarm the markup, not to cut out data (the fact
   * must still be readable, otherwise neutralization itself becomes memory loss).
   */
  fun neutralize(tag: String, raw: String): String =
    Regex("(?i)<(/?)${Regex.escape(tag)}").replace(raw) { m -> "<${m.groupValues[1]}_$tag" }

  /**
   * [body] in a `<tag …>` / `</tag>` envelope. [body] must already have gone through [neutralize] — the
   * function deliberately does not do it itself: the caller often assembles the body from SEVERAL pieces
   * (profile, facts, episodes), each neutralized in its own place, and neutralizing the finished whole
   * after the fact would spoil the markup already added around them.
   */
  fun wrap(tag: String, body: String, attributes: String = ""): String {
    val open = if (attributes.isBlank()) "<$tag>" else "<$tag $attributes>"
    return "$open\n$body\n</$tag>"
  }
}
