package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.llm.FunctionSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a function-call JSON schema for a [Tool] without the nested `JsonObject(mapOf(...))`
 * boilerplate every tool used to repeat.
 */
class ToolSchemaBuilder(private val toolName: String, private val toolDescription: String) {
  private val properties = LinkedHashMap<String, JsonObject>()
  private val required = mutableListOf<String>()

  fun string(
    name: String,
    description: String,
    required: Boolean = false,
    enum: List<String>? = null,
  ) = addParam(name, "string", description, required, enum)

  fun integer(name: String, description: String, required: Boolean = false) =
    addParam(name, "integer", description, required)

  fun obj(name: String, description: String, required: Boolean = false) =
    addParam(name, "object", description, required)

  fun boolean(name: String, description: String, required: Boolean = false) =
    addParam(name, "boolean", description, required)

  /**
   * An array of strings. [enum], if given, constrains the ITEMS (JSON Schema `items.enum`), not the array
   * itself, so the model's constrained decoding offers only the allowed values for each entry.
   */
  fun stringArray(
    name: String,
    description: String,
    required: Boolean = false,
    enum: List<String>? = null,
  ) {
    properties[name] = JsonObject(
      mapOf(
        "type" to JsonPrimitive("array"),
        "description" to JsonPrimitive(description),
        "items" to JsonObject(
          buildMap {
            put("type", JsonPrimitive("string"))
            if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
          },
        ),
      ),
    )
    if (required) this.required += name
  }

  private fun addParam(
    name: String,
    jsonType: String,
    description: String,
    required: Boolean,
    enum: List<String>? = null,
  ) {
    properties[name] = JsonObject(
      buildMap {
        put("type", JsonPrimitive(jsonType))
        put("description", JsonPrimitive(description))
        // JSON Schema requires enum to be an ARRAY — otherwise the model's constrained decoding ignores it.
        if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
      },
    )
    if (required) this.required += name
  }

  fun build(): FunctionSchema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = toolName,
      description = toolDescription,
      parameters = JsonObject(
        mapOf(
          "type" to JsonPrimitive("object"),
          "properties" to JsonObject(properties),
          "required" to JsonArray(required.map { JsonPrimitive(it) }),
        ),
      ),
    ),
  )
}

/** Entry point: `toolSchema(name, desc) { string("action", "...", required = true) }`. */
fun toolSchema(
  name: String,
  description: String,
  block: ToolSchemaBuilder.() -> Unit,
): FunctionSchema = ToolSchemaBuilder(name, description).apply(block).build()

/** Read a non-blank string argument, or null. Replaces the `(args[k] as? JsonPrimitive)?.content` dance. */
fun Map<String, JsonElement>.string(key: String): String? =
  (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

/**
 * Read a list-of-strings argument, or null when the key is absent. Accepts a JSON array AND a
 * comma-separated string: models fill an array parameter either way, and a tool that rejected the second
 * form would make the model retry a call that was fine in meaning. Blank entries are dropped.
 */
fun Map<String, JsonElement>.stringList(key: String): List<String>? = when (val raw = this[key]) {
  null, is JsonNull -> null
  is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { s -> s.isNotEmpty() } }
  is JsonPrimitive -> raw.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }
  else -> null
}

/** Read a boolean argument (accepts a JSON boolean or the strings "true"/"false"), or null. */
fun Map<String, JsonElement>.boolean(key: String): Boolean? =
  (this[key] as? JsonPrimitive)?.content?.trim()?.lowercase()?.let {
    when (it) {
      "true" -> true
      "false" -> false
      else -> null
    }
  }

/** Read an integer argument (accepts numeric or string-numeric JSON values), or null. */
fun Map<String, JsonElement>.int(key: String): Int? =
  (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

/** Read a long argument (accepts numeric or string-numeric JSON values), or null. */
fun Map<String, JsonElement>.long(key: String): Long? =
  (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

/** Standard "Missing 'key'" error so tools can write `args.string(k) ?: return missingArg(k)`. */
fun missingArg(key: String): ToolResult.Error = ToolResult.Error("Missing '$key'")
