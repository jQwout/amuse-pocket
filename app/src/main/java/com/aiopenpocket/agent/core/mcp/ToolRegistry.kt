package com.aiopenpocket.agent.core.mcp

// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.llm.FunctionSchema
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
@Inject
class ToolRegistry(
  tools: Set<@JvmSuppressWildcards Tool>,
) {
  private val toolMap = tools.associateBy { it.name }

  fun get(name: String): Tool? = toolMap[name]
  fun getAll(): List<Tool> = toolMap.values.toList()
  fun getAllSchemas(): List<FunctionSchema> = toolMap.values.map { it.schema }

  /** Schemas for the named tools only — used to scope a mini-agent to its own tool set. */
  fun getSchemasFor(names: Set<String>): List<FunctionSchema> =
    toolMap.filterKeys { it in names }.values.map { it.schema }
}
