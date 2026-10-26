package com.aiopenpocket.agent.core.prompt

// @trace agent-memory-05e545

import com.aiopenpocket.agent.core.memory.model.Episode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A memory episode as one line for the model: `[tool action] start of the result`.
 *
 * An episode stores the tool call's result WHOLE (a file read — up to 40k characters, a storage answer
 * — up to 100k), while "Recent actions" are put into EVERY system prompt of the conversation. A raw
 * result meant one read file rode along in every following turn until five fresher calls pushed it
 * out. The model needs the fact of the event — "the alarm is already set", "the file was read" — not the
 * content: if needed, that is read again. So it is cut ON DISPLAY, not on write: the database keeps
 * the full trace for auditing.
 */
object EpisodeFormat {

  /** How many characters of the result the model sees: enough for "done/error + the gist". */
  const val OUTCOME_MAX_CHARS = 300

  private val WHITESPACE = Regex("\\s+")

  fun line(episode: Episode): String = "[${label(episode)}] ${shortOutcome(episode.outcome)}"

  /** The result on one line (line breaks would break the list) and no longer than [OUTCOME_MAX_CHARS]. */
  fun shortOutcome(outcome: String): String {
    val flat = outcome.replace(WHITESPACE, " ").trim()
    return if (flat.length <= OUTCOME_MAX_CHARS) flat else flat.take(OUTCOME_MAX_CHARS).trimEnd() + "…"
  }

  /**
   * The tool name and its `action` from the episode payload: the bare `tool_call` type tells the model
   * nothing — "what exactly was done" is the useful part of the event. An unreadable payload is no
   * reason to lose the line; then the label is just the episode type.
   */
  private fun label(episode: Episode): String {
    val payload = runCatching { Json.parseToJsonElement(episode.payload) as? JsonObject }.getOrNull()
      ?: return episode.type
    val name = (payload["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return episode.type
    val action = ((payload["arguments"] as? JsonObject)?.get("action") as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.takeIf { it.isNotBlank() }
    return if (action == null) name else "$name $action"
  }
}
