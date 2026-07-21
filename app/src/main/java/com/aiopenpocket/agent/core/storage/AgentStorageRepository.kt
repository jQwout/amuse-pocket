package com.aiopenpocket.agent.core.storage

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Room-backed per-agent key-value store (Part B, `user-agent-builder-848153`) - the structured write
 * path `database_query`'s Part A hardening pushes agents toward instead of raw SQL. Every method takes
 * [agentId] as a plain parameter and trusts whatever its caller passes - exactly like
 * [com.aiopenpocket.agent.core.memory.repository.MemoryRepository]'s namespace-scoped methods trust
 * their `namespace` parameter (the same split Task 5's memory-isolation work established). The actual
 * ISOLATION guarantee is enforced one layer up, in `StorageTool`, which reads the id from
 * [com.aiopenpocket.agent.loop.TurnContextHolder] rather than accepting it as a tool argument the
 * model could set - this repository is not itself where that guarantee lives.
 */
@SingleIn(AppScope::class)
@Inject
class AgentStorageRepository(
  private val dao: AgentStorageDao,
) {
  /** Per-key upsert (not a whole-collection replace) - see [AgentStorageEntity]'s kdoc. */
  suspend fun put(agentId: String, collection: String, key: String, valueJson: String) {
    dao.upsert(AgentStorageEntity(agentId, collection, key, valueJson, System.currentTimeMillis()))
  }

  suspend fun get(agentId: String, collection: String, key: String): AgentStorageEntity? =
    dao.get(agentId, collection, key)

  /**
   * Every entry in ([agentId], [collection]), sorted by key - optionally narrowed to keys starting
   * with [keyPrefix]. Filtered in Kotlin rather than a SQL `LIKE ... ESCAPE` clause: a per-agent
   * scratch store is never expected to hold enough rows per collection for this to matter
   * performance-wise, and it sidesteps having to escape `%`/`_`/the escape character itself out of a
   * caller-supplied prefix before it could safely reach a LIKE pattern.
   */
  suspend fun list(agentId: String, collection: String, keyPrefix: String? = null): List<AgentStorageEntity> {
    val all = dao.listByCollection(agentId, collection)
    return if (keyPrefix.isNullOrEmpty()) all else all.filter { it.key.startsWith(keyPrefix) }
  }

  suspend fun delete(agentId: String, collection: String, key: String) {
    dao.delete(agentId, collection, key)
  }

  /**
   * Wipes every entry owned by [agentId], across every collection. Not currently called by
   * `StorageTool` itself (which only ever acts within the current turn's own bound agent) - exposed
   * for a future "delete/forget this agent" cleanup path, mirroring how
   * [com.aiopenpocket.agent.core.hook.AgentHookRepository] has no such wholesale-delete today either
   * (a user-agent delete currently archives rather than purges - see [com.aiopenpocket.agent.core.agent.AgentStatus.ARCHIVED]).
   */
  suspend fun deleteByAgent(agentId: String) {
    dao.deleteByAgent(agentId)
  }
}
