package com.aiopenpocket.agent.core.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Central, schema-driven argument check applied once in [ToolExecutor] before a tool runs, instead
 * of each tool re-validating ad hoc. Best-practices: tool schemas must be "validated locally".
 *
 * Deliberately conservative — it enforces only the two contract facts that are unambiguous from the
 * declared JSON schema:
 *  - every `required` field is present, and
 *  - any present field that declares an `enum` holds one of the allowed values.
 *
 * It does NOT type-coerce or reject extra keys: several tools intentionally accept integers encoded
 * as JSON strings, and tightening that here would regress well-formed calls.
 */
object SchemaValidator {

  /** Returns a [ToolResult.Error] describing the first violation, or null if arguments are valid. */
  fun validate(schema: com.aiopenpocket.agent.core.llm.FunctionSchema, arguments: Map<String, JsonElement>): ToolResult.Error? {
    val params = schema.function.parameters
    val properties = params["properties"] as? JsonObject

    (params["required"] as? JsonArray)?.forEach { req ->
      val field = (req as? JsonPrimitive)?.content ?: return@forEach
      if (arguments[field] == null) {
        return ToolResult.Error("Invalid arguments for '${schema.function.name}': missing required '$field'.", recoverable = false)
      }
    }

    if (properties != null) {
      for ((field, value) in arguments) {
        val prop = properties[field] as? JsonObject ?: continue
        val enum = prop["enum"] as? JsonArray ?: continue
        val provided = (value as? JsonPrimitive)?.content ?: continue
        val allowed = enum.mapNotNull { (it as? JsonPrimitive)?.content }
        if (provided !in allowed) {
          return ToolResult.Error(
            "Invalid arguments for '${schema.function.name}': '$field'='$provided' is not one of ${allowed.joinToString(", ")}.",
            recoverable = false,
          )
        }
      }
    }
    return null
  }
}
