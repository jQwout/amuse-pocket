package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b
// @trace streaming-llm-chat-6595a1

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A tool call whose arguments did not parse as a JSON object.
 *
 * The model used to get an empty argument map in that case, and the tool answered "missing argument
 * `x`" — so the model "fixed" the wrong thing (re-guessed the value) while the real fault, broken JSON,
 * stayed. The marker travels INSIDE the arguments map because that map is the only thing between the
 * stream parser and [ToolExecutor] (it passes through the loop and the approval gate untouched), and
 * [ToolExecutor] turns it into a result that names the real problem.
 *
 * The key cannot collide with a real argument: schemas declare their own property names, and nothing
 * starts with a double underscore.
 */
object MalformedArguments {
  const val KEY = "__arguments_parse_error"
  private const val EXCERPT_CHARS = 160

  /** The map to hand on for [raw] text that failed to parse. Carries a short excerpt, not the whole blob. */
  fun marker(raw: String): Map<String, JsonElement> {
    val flat = raw.replace(Regex("\\s+"), " ").trim()
    val excerpt = if (flat.length > EXCERPT_CHARS) flat.take(EXCERPT_CHARS) + "…" else flat
    return mapOf(KEY to JsonPrimitive(excerpt))
  }

  /** The excerpt if [arguments] is a marker, null for real arguments. */
  fun excerptOf(arguments: Map<String, JsonElement>): String? =
    (arguments[KEY] as? JsonPrimitive)?.contentOrNull

  /** What the model reads in the tool result. English: it is for the model, not for the user. */
  fun message(toolName: String, excerpt: String): String =
    "Tool '$toolName' was not executed: its arguments were not valid JSON (received: $excerpt). " +
      "Resend the call with the arguments as one valid JSON object."
}
