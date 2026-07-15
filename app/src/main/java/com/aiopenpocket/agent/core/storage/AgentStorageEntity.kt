package com.aiopenpocket.agent.core.storage

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import androidx.room.Entity
import androidx.room.Index

/**
 * Room row for one per-agent, free-form key-value entry (Part B, `user-agent-builder-848153`'s
 * `StorageTool`) - the structured-write counterpart to `database_query`'s Part A SELECT-only
 * hardening: instead of raw SQL, an agent that needs to persist its OWN working state (a scratchpad,
 * counters, small JSON blobs) writes through this table via [AgentStorageRepository]/`StorageTool`,
 * which enforce the [agentId] scoping this entity itself does not - see `StorageTool`'s kdoc for why
 * that enforcement deliberately lives one layer up, in code that reads
 * [com.aiopenpocket.agent.loop.TurnContextHolder] rather than trusting a caller-supplied id.
 *
 * Composite primary key ([agentId], [collection], [key]) - `collection` is the agent's own free-form
 * grouping (e.g. "notes", "counters"), `key` the entry within it. [put] is a per-key upsert, not a
 * whole-collection replace (ordinary KV-store semantics) - unlike
 * [com.aiopenpocket.agent.core.hook.AgentHookRepository.replaceForAgent]'s wholesale replace, which
 * fits hooks' "the whole set republishes together" semantics but would be the wrong fit here.
 */
@Entity(
  tableName = "agent_storage",
  primaryKeys = ["agentId", "collection", "key"],
  indices = [Index("agentId", "collection")],
)
data class AgentStorageEntity(
  val agentId: String,
  val collection: String,
  val key: String,
  val valueJson: String,
  val updatedAt: Long,
)
