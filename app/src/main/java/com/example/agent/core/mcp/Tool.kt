package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.llm.FunctionSchema
import kotlinx.serialization.json.JsonElement

interface Tool {
  val name: String
  val description: String
  val schema: FunctionSchema

  /**
   * Runtime risk of this tool's actions. Defaults to [RiskClass.READ]; tools with side effects
   * override it so the harness can apply the right policy. For multi-action tools, classify by the
   * highest-risk action the tool can perform.
   */
  val riskClass: RiskClass get() = RiskClass.READ

  /**
   * True when this tool returns content that originates outside the app's trust boundary (incoming
   * SMS, calendar entries authored by others, files, future web/connector data). Such output is
   * wrapped in an untrusted envelope before it re-enters the model, so injected instructions inside
   * it are treated as data, never commands.
   */
  val returnsUntrustedContent: Boolean get() = false

  suspend fun execute(arguments: Map<String, JsonElement>): ToolResult
}
