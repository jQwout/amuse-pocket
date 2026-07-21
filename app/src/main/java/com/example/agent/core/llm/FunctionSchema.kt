package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class FunctionSchema(
  val type: String = "function",
  val function: FunctionDefinition,
) {
  @Serializable
  data class FunctionDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
  )
}
