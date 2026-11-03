package com.aiopenpocket.voice.call

// @trace voice-call-mode-54cb49

/**
 * Cuts a reply that is still being written into pieces the voice can start reading right away.
 *
 * In a call the person should hear the first sentence about a second after the model starts writing,
 * not after the whole reply is done. So every new value of the turn's stream goes through [onStream],
 * which hands out only sentences that are already complete; the tail comes out of [onFinal] once the
 * reply is saved.
 *
 * The stream is not a simple growing string. After a tool call the next iteration's text starts from
 * scratch, so a value that does not continue what was already read is a new segment and reading
 * restarts there. (Tool progress lines are not fed here at all: the caller drops them.)
 */
class SentenceFeed {
  /** The current segment as last seen. */
  private var segment = ""

  /** How much of [segment] was already handed out. */
  private var handedOut = 0

  /** The last piece handed out in this segment, to find where the saved reply continues after it. */
  private var lastPiece: String? = null

  /** Returns the complete sentences of [text] not handed out before, or null if there are none yet. */
  fun onStream(text: String): String? {
    if (!text.startsWith(segment.substring(0, handedOut))) {
      handedOut = 0
      lastPiece = null
    }
    segment = text
    val end = lastSentenceEnd(segment, from = handedOut)
    if (end <= handedOut) return null
    return handOut(segment.substring(handedOut, end)).also { handedOut = end }
  }

  /**
   * Returns what is left to read of the saved reply [text]. If part of this segment was read already,
   * the rest is whatever follows the last read piece; if that piece is not in the saved reply (the
   * final text differs from what was streamed), nothing more is read rather than repeating the reply.
   */
  fun onFinal(text: String): String? {
    val last = lastPiece
    val rest = if (last == null) {
      text
    } else {
      val at = text.lastIndexOf(last)
      if (at < 0) "" else text.substring(at + last.length)
    }
    segment = ""
    handedOut = 0
    lastPiece = null
    return handOut(rest)
  }

  private fun handOut(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }?.also { lastPiece = it }

  private companion object {
    /**
     * The end (exclusive) of the last complete sentence in [text] at or after [from]: a sentence mark
     * followed by whitespace, or a line break. "3.5" is not a break, and the very end of the text is
     * not one either: the next token may still continue the sentence.
     */
    fun lastSentenceEnd(text: String, from: Int): Int {
      var end = from
      for (i in from until text.length - 1) {
        val c = text[i]
        if (c == '\n' || (c in SENTENCE_MARKS && text[i + 1].isWhitespace())) end = i + 1
      }
      return end
    }

    const val SENTENCE_MARKS = ".!?…:;"
  }
}
