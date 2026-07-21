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
   * Runtime risk of THIS specific call — defaults to the tool-wide [riskClass], which is exactly
   * right for single-action tools. A multi-action tool (one `action` argument selecting between e.g.
   * a read and a delete) overrides this to classify per action instead, so
   * `com.aiopenpocket.agent.core.policy.ExecutionPolicy` sees a `list_events` call as [RiskClass.READ]
   * even though the same tool's `delete_event` is [RiskClass.DESTRUCTIVE] — the tool-wide [riskClass]
   * stays the ceiling (used e.g. by [com.aiopenpocket.agent.loop.ReactLoopRunner]'s episodic-memory
   * logging, which has no per-call argument context to re-derive this from).
   *
   * Called BEFORE [execute] (the policy gate runs ahead of [com.aiopenpocket.agent.core.mcp.ToolExecutor]),
   * so an override must classify from [arguments] alone — it cannot assume the schema/required-field
   * validation [com.aiopenpocket.agent.core.mcp.ToolExecutor] normally does first has already run.
   * Never throws: an override that can't determine the action from [arguments] should fail closed to a
   * safe, conservative classification rather than throw (a missing/malformed argument is still caught
   * by [execute]'s own validation once the gate lets the call through).
   */
  fun riskFor(arguments: Map<String, JsonElement>): RiskClass = riskClass

  /**
   * True when this tool returns content that originates outside the app's trust boundary (incoming
   * SMS, calendar entries authored by others, files, future web/connector data). Such output is
   * wrapped in an untrusted envelope before it re-enters the model, so injected instructions inside
   * it are treated as data, never commands.
   */
  val returnsUntrustedContent: Boolean get() = false

  suspend fun execute(arguments: Map<String, JsonElement>): ToolResult
}
