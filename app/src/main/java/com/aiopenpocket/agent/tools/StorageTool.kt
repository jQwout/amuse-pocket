package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.storage.AgentStorageRepository
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

/**
 * Per-agent persistent key-value storage (Part B, `user-agent-builder-848153`) - the structured write
 * path `database_query`'s Part A SELECT-only hardening pushes agents toward for anything beyond facts/
 * episodes (which stay [MemoryTools]' job). An agent can `put`/`get`/`list`/`delete` an arbitrary JSON
 * value under its own `collection`/`key` - a scratchpad, counters, small structured state - without
 * ever touching raw SQL.
 *
 * ISOLATION IN CODE, not by prompt instruction (mirrors [MemoryTools]'s Task 5 framing exactly): the
 * `agentId` every call is scoped under comes ONLY from [turnContextHolder] - the currently bound
 * agent's own id - and is NEVER a tool argument. Whatever the model puts in its `collection`/`key`
 * arguments, it physically cannot address another agent's storage, because there is no argument
 * through which it could ever name a different agentId; [AgentStorageRepository] just does whatever
 * this tool tells it to, exactly as trusting as
 * [com.aiopenpocket.agent.core.memory.repository.MemoryRepository]'s own namespace-scoped methods.
 *
 * Plain chat (no bound agent) uses the fixed [DEFAULT_NAMESPACE] rather than null or an empty string,
 * so its rows still have a real, stable owner key. [DEFAULT_NAMESPACE] is deliberately NOT a string
 * [com.aiopenpocket.agent.core.agent.AgentValidator.ID_REGEX] could ever accept as a real agent id (that
 * pattern requires the FIRST character to be `[a-z0-9]`, never `_`) - so no future custom agent id can
 * ever collide with plain chat's namespace and inherit its rows.
 */
@Inject
@SingleIn(AppScope::class)
class StorageTool(
  private val storageRepository: AgentStorageRepository,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  // Values are arbitrary agent-authored JSON round-tripped back through get/list - treat exactly like
  // any other stored external text (mirrors DatabaseQueryTool/FileTools).
  override val returnsUntrustedContent = true

  override val name = "storage_tool"
  override val description =
    "Персональное key-value хранилище агента: сохраняет и читает произвольные JSON-значения по " +
    "коллекции и ключу (например, заметки или счётчики). Изолировано по агенту в коде — один " +
    "агент физически не может увидеть данные другого."
  override val schema = toolSchema(name, description) {
    string(
      "action", "Действие: put, get, list, delete", required = true,
      enum = listOf("put", "get", "list", "delete"),
    )
    string("collection", "Группа записей, например \"notes\" или \"counters\"", required = true)
    string("key", "Ключ записи (для put/get/delete)")
    string("value", "Значение в виде JSON-строки (для put), максимум $MAX_VALUE_CHARS символов")
    string("key_prefix", "Необязательный префикс ключа для фильтрации (для list)")
  }

  // Per-call risk (Task 9 convention, mirrors MemoryTools/FileTools): put mutates this agent's storage,
  // get/list only read it. An unrecognized/missing action fails closed to READ - execute() rejects it
  // before anything runs either way.
  //
  // delete — DESTRUCTIVE: запись исчезает без корзины и без версии, вернуть её нечем. put тоже может
  // перезаписать значение по тому же ключу, но это основной путь записи агента в СОБСТВЕННЫЙ блокнот —
  // спрашивать разрешение на каждое «сохрани» значило бы сломать сам смысл хранилища, поэтому WRITE.
  override val riskClass = RiskClass.DESTRUCTIVE

  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "put" -> RiskClass.WRITE
    "delete" -> RiskClass.DESTRUCTIVE
    else -> RiskClass.READ
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    // Snapshot ONCE per call, exactly like FileTools.resolveSandboxDir - every branch below uses this
    // SAME agentId, even across a suspension inside this call (see TurnContextHolder's kdoc on why
    // tools must not re-read turnContextHolder.agent a second time mid-call).
    val agentId = turnContextHolder.agentId ?: DEFAULT_NAMESPACE
    return when (val action = arguments.string("action") ?: return missingArg("action")) {
      "put" -> put(agentId, arguments)
      "get" -> get(agentId, arguments)
      "list" -> list(agentId, arguments)
      "delete" -> delete(agentId, arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private suspend fun put(agentId: String, args: Map<String, JsonElement>): ToolResult {
    val collection = args.string("collection") ?: return missingArg("collection")
    val key = args.string("key") ?: return missingArg("key")
    val value = args.string("value") ?: return missingArg("value")
    labelError(collection, "collection")?.let { return it }
    labelError(key, "key")?.let { return it }
    if (value.length > MAX_VALUE_CHARS) {
      return ToolResult.Error("Значение 'value' слишком длинное (${value.length} символов, максимум $MAX_VALUE_CHARS).")
    }
    storageRepository.put(agentId, collection, key, value)
    return ToolResult.Success("Сохранено: $collection/$key")
  }

  private suspend fun get(agentId: String, args: Map<String, JsonElement>): ToolResult {
    val collection = args.string("collection") ?: return missingArg("collection")
    val key = args.string("key") ?: return missingArg("key")
    labelError(collection, "collection")?.let { return it }
    labelError(key, "key")?.let { return it }
    val entry = storageRepository.get(agentId, collection, key)
    return ToolResult.Success(entry?.valueJson ?: "null")
  }

  private suspend fun list(agentId: String, args: Map<String, JsonElement>): ToolResult {
    val collection = args.string("collection") ?: return missingArg("collection")
    labelError(collection, "collection")?.let { return it }
    val prefix = args.string("key_prefix")
    val entries = storageRepository.list(agentId, collection, prefix)
    return if (entries.isEmpty()) {
      ToolResult.Success("Нет записей в '$collection'" + (prefix?.let { " с префиксом '$it'" }.orEmpty()) + ".")
    } else {
      ToolResult.Success(entries.joinToString("\n") { "- ${it.key}: ${it.valueJson}" })
    }
  }

  private suspend fun delete(agentId: String, args: Map<String, JsonElement>): ToolResult {
    val collection = args.string("collection") ?: return missingArg("collection")
    val key = args.string("key") ?: return missingArg("key")
    labelError(collection, "collection")?.let { return it }
    labelError(key, "key")?.let { return it }
    storageRepository.delete(agentId, collection, key)
    return ToolResult.Success("Удалено: $collection/$key")
  }

  /** Null iff [value] is within [MAX_LABEL_CHARS] - shared cap for both `collection` and `key`. */
  private fun labelError(value: String, field: String): ToolResult.Error? =
    if (value.length > MAX_LABEL_CHARS) {
      ToolResult.Error("Поле '$field' слишком длинное (${value.length} символов, максимум $MAX_LABEL_CHARS).")
    } else {
      null
    }

  private companion object {
    const val MAX_VALUE_CHARS = 100_000
    const val MAX_LABEL_CHARS = 128

    /** See this class's own kdoc for why this exact string is safe against ever colliding with a real agent id. */
    const val DEFAULT_NAMESPACE = "__plain_chat__"
  }
}
