package com.aiopenpocket.ui.chat

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153

import com.aiopenpocket.i18n.tr

/**
 * The rules of the `multi_choice` card, kept out of the composable so plain JUnit can run them: which
 * ticks a tap produces, when "Next" is allowed, what text goes back to the model, and what is stored in
 * `messages.cardState` so the card stays locked after the person leaves the chat and returns.
 *
 * Why the card exists (owner, 31 Oct 2026): the agent builder is a chat, and the model asks a few
 * questions at a time, each with 3-4 options to tick. The single `choice` card answers on the first tap,
 * which cannot express "Memory AND Reminders".
 */
object MultiChoiceSelection {

  /**
   * The ticks after a tap on [index]. A tapped tick is removed. A new tick is added only while fewer than
   * [max] are set — except when [max] is 1, where a new tap REPLACES the tick (a radio button: nobody
   * should have to untick before choosing another). Ignoring the tap at the limit, rather than dropping
   * the oldest tick, keeps what the person chose until they remove it themselves.
   */
  fun toggle(selected: Set<Int>, index: Int, max: Int): Set<Int> = when {
    index in selected -> selected - index
    max == 1 -> setOf(index)
    selected.size < max -> selected + index
    else -> selected
  }

  /** Whether "Next" may be pressed with these ticks. */
  fun canSubmit(selected: Set<Int>, min: Int, max: Int): Boolean = selected.size in min..max

  /**
   * The text sent back as the user's message: the labels of the ticked options in the card's order,
   * joined with ", " ("Memory, Reminders"). Card order rather than tap order so the same choice always
   * reads the same. An empty choice (only possible when `min` is 0) is said in words: an empty user
   * message would be dropped.
   */
  fun answerText(options: List<String>, selected: Set<Int>): String =
    selected.sorted().mapNotNull { options.getOrNull(it) }.joinToString(", ")
      .ifEmpty { tr("Ничего из этого", "None of these") }

  private const val SUBMITTED_PREFIX = "done:"

  /** The `cardState` of a card that was answered with [selected]. */
  fun encodeSubmitted(selected: Set<Int>): String = SUBMITTED_PREFIX + selected.sorted().joinToString(",")

  /**
   * The ticks of an answered card, or null when [state] says it was not answered (null, or anything that
   * is not ours). Indexes past [optionCount] are dropped: a stale index would show a tick on an option
   * that is not there.
   */
  fun decodeSubmitted(state: String?, optionCount: Int): Set<Int>? {
    if (state == null || !state.startsWith(SUBMITTED_PREFIX)) return null
    return state.removePrefix(SUBMITTED_PREFIX).split(',')
      .mapNotNull { it.trim().toIntOrNull() }
      .filter { it in 0 until optionCount }
      .toSet()
  }
}
