package com.aiopenpocket.ui.chat.components

// @trace mcp-tools-f77d9b
// @trace english-ui-on-the-demo-path-223241

import com.aiopenpocket.i18n.tr
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** One argument of a call as the approval bar shows it: a human label and a single-line value. */
internal data class ToolArgumentLine(val label: String, val value: String)

/**
 * The arguments of a tool call, ordered so that the facts a person needs in order to decide come
 * first.
 *
 * The bar is cut to a few lines, and a JSON object keeps whatever order the model wrote it in. For an
 * email draft that order began with `body`, so the card said `body: Hi Masha, …` and the two facts
 * that decide the answer — the recipient and the subject — never reached the screen. The project's
 * floor is that EXTERNAL always asks ([com.aiopenpocket.agent.core.policy.ExecutionPolicy]); asking
 * without the facts is worse than not asking.
 *
 * The rule is general, not the email tool's field names: first WHO/WHERE it goes to, then what it is
 * called, then the rest in the model's own order, and free text (a letter's body, a push's text) last —
 * that is where the ellipsis can land without hiding anything that matters. A tool with no such
 * arguments simply shows all of them, briefly.
 */
internal fun toolArgumentLines(toolName: String, arguments: Map<String, JsonElement>): List<ToolArgumentLine> =
    arguments.entries
        // `action` (and a skill's `id` for load_skill) is already said in the bar's title through
        // CapabilityNames.call — repeating it below as a raw id tells the reader nothing new.
        .filterNot { (key, _) -> key == "action" || (toolName == LOAD_SKILL && key == "id") }
        // Stable sort: inside one rank the model's own order is kept, so nothing is shuffled for show.
        .sortedBy { (key, _) -> rank(key) }
        .map { (key, value) -> ToolArgumentLine(label(key), oneLine(value)) }

/** The same lines as one block of text — one argument per line, so a long body cannot push out the rest. */
internal fun formatToolArguments(toolName: String, arguments: Map<String, JsonElement>): String =
    toolArgumentLines(toolName, arguments).joinToString("\n") { "${it.label}: ${it.value}" }

private const val LOAD_SKILL = "load_skill"

/** Who or where the call goes to — the first thing a person weighing "allow" needs. */
private val ADDRESSEE = setOf("to", "recipient", "recipients", "email", "phone", "path", "uri")

/** What it is called or which record it is about: enough to recognise the thing without reading its text. */
private val SUBJECT = setOf("subject", "title", "term", "key", "collection", "template", "query", "location")

/** Free text, as long as the model felt like: shown last, where being cut off costs the least. */
private val BULK = setOf("body", "message", "text", "content", "value", "description", "example", "prompt", "params")

private fun rank(key: String): Int = when (key.lowercase()) {
    in ADDRESSEE -> 0
    in SUBJECT -> 1
    in BULK -> 3
    else -> 2
}

/**
 * A readable label instead of the raw argument name. One dictionary for every tool — the names repeat
 * across them (`title`, `message`, `path`), and an unknown one is shown as it is, without underscores:
 * a strange word is better than a missing line.
 */
private fun label(key: String): String = when (key.lowercase()) {
    "to", "recipient", "recipients", "email" -> tr("Кому", "To")
    "subject" -> tr("Тема", "Subject")
    "title" -> tr("Заголовок", "Title")
    "body" -> tr("Письмо", "Body")
    "message", "text" -> tr("Текст", "Text")
    "path", "uri" -> tr("Файл", "File")
    "query" -> tr("Поиск", "Search")
    "term" -> tr("Слово", "Word")
    "translation" -> tr("Перевод", "Translation")
    "key" -> tr("Ключ", "Key")
    "value" -> tr("Значение", "Value")
    "content" -> tr("Содержимое", "Content")
    "location" -> tr("Место", "Place")
    else -> key.replace('_', ' ')
}

/**
 * The value on one line: a string without its JSON quotes, line breaks collapsed (a letter's body
 * arrives with them, and four of its own lines would push the recipient off the card) and a long tail
 * cut — the bar is a summary, the full arguments are in the call log.
 */
private fun oneLine(value: JsonElement): String {
    val raw = (value as? JsonPrimitive)?.content ?: value.toString()
    val flat = raw.replace(WHITESPACE, " ").trim()
    return if (flat.length <= MAX_VALUE_CHARS) flat else flat.take(MAX_VALUE_CHARS).trimEnd() + "…"
}

private val WHITESPACE = Regex("\\s+")

private const val MAX_VALUE_CHARS = 200
