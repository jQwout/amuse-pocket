package com.aiopenpocket.voice.output

// @trace voice-assistant-mode-a81a99

import com.aiopenpocket.agent.core.prompt.REFLECTION_FINAL_HEADING

/**
 * Turns an assistant reply (Markdown) into text a speech engine can read. The engine reads markup
 * literally: "asterisk asterisk", "hash", a whole URL letter by letter. So the markup goes and only
 * the words stay. Code blocks are dropped too: they make no sense read aloud, and they stay on screen.
 *
 * A reflection reply holds the draft, the critique and the result; only the result is read, the same
 * part the model sees in later turns (ContextWindowBuilder).
 */
internal fun speakableText(markdown: String): String {
  val reply = if (markdown.contains(REFLECTION_FINAL_HEADING)) {
    markdown.substringAfterLast(REFLECTION_FINAL_HEADING)
  } else {
    markdown
  }
  return reply
    .replace(CODE_BLOCK, " ")
    .replace(IMAGE, "")
    .replace(LINK) { it.groupValues[1] }
    .replace(BARE_URL, "")
    .replace(INLINE_CODE) { it.groupValues[1] }
    .lines()
    .map { line ->
      line
        .replace(HEADING, "")
        .replace(QUOTE, "")
        .replace(BULLET, "")
        .replace(TABLE_RULE, "")
        .replace(HORIZONTAL_RULE, "")
        .replace('|', ',')
        .replace(EMPHASIS, "")
        .replace(SPACES, " ")
        .trim(' ', ',')
    }
    .filter { it.isNotBlank() }
    .joinToString("\n")
    .trim()
}

/**
 * Splits [text] into pieces of at most [maxLength] characters for `TextToSpeech.speak`, which refuses
 * longer input. It cuts at a paragraph, then a sentence, then a space, and only then mid-word.
 */
internal fun speechChunks(text: String, maxLength: Int): List<String> {
  require(maxLength > 0)
  val chunks = mutableListOf<String>()
  var rest = text.trim()
  while (rest.length > maxLength) {
    val window = rest.substring(0, maxLength)
    val cut = listOf("\n", ". ", "! ", "? ", "; ", ", ", " ")
      .firstNotNullOfOrNull { sep -> window.lastIndexOf(sep).takeIf { it > 0 }?.plus(sep.length) }
      ?: maxLength
    chunks += rest.substring(0, cut).trim()
    rest = rest.substring(cut).trim()
  }
  if (rest.isNotEmpty()) chunks += rest
  return chunks.filter { it.isNotEmpty() }
}

private val CODE_BLOCK = Regex("```[\\s\\S]*?(```|$)")
private val IMAGE = Regex("!\\[[^\\]]*]\\([^)]*\\)")
private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
private val BARE_URL = Regex("https?://\\S+")
private val INLINE_CODE = Regex("`([^`]*)`")
private val HEADING = Regex("^\\s{0,3}#{1,6}\\s*")
private val QUOTE = Regex("^\\s*>\\s?")
private val BULLET = Regex("^\\s*([-*+]|\\d+[.)])\\s+")
private val TABLE_RULE = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")
private val HORIZONTAL_RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val EMPHASIS = Regex("(\\*\\*|__|\\*|~~|(?<![\\p{L}\\d])_|_(?![\\p{L}\\d]))")
private val SPACES = Regex("[ \t]{2,}")
