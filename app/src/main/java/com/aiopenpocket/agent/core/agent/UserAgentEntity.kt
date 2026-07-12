package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aiopenpocket.agent.loop.AgentMode

/**
 * Room row for one [AgentSource.USER] agent's draft/published/archived snapshot. Only user-authored
 * agents ever live here — presets stay in the read-only [MiniAgentCatalog] Kotlin list and are never
 * written to this table, so there is no `source` column: [toDefinition] always reports
 * [AgentSource.USER]. [AgentDefinition.hooks] is not stored yet either (Task 8 adds
 * `AgentHookEntity`/`AgentHookDao`); until then [toDefinition] always returns `hooks = emptyList()`.
 *
 * Collections are declared as `List<String>` so they all share the one [AgentRoomConverters]
 * JSON<->`List<String>` pair; [allowedModes] carries [AgentMode.name] strings rather than a typed
 * enum collection so it can reuse that same converter instead of needing a second one.
 * [defaultMode]/[status] are plain `.name` strings — Room persists `String` columns natively, no
 * converter needed for those two.
 *
 * [AgentDefinition.stateKeyPrefixes] is not stored either — every USER agent's [toDefinition]
 * reports `emptySet()` for it (no builder UI configures it yet; see that property's own KDoc).
 */
@Entity(tableName = "user_agents")
data class UserAgentEntity(
  @PrimaryKey val id: String,
  val name: String,
  val description: String,
  val iconKey: String,
  val systemPrompt: String,
  val toolNames: List<String>,
  val skillIds: List<String>,
  val allowedModes: List<String>,
  val allowedWidgetTemplates: List<String>,
  val defaultMode: String,
  val memoryNamespace: String,
  val includeSharedProfile: Boolean,
  val status: String,
  val configVersion: Long,
  val createdAt: Long,
  val updatedAt: Long,
) {
  /** Reconstructs the runtime [AgentDefinition] snapshot this row represents. */
  fun toDefinition(): AgentDefinition = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = name,
    description = description,
    iconKey = iconKey,
    systemPrompt = systemPrompt,
    toolNames = toolNames.toSet(),
    skillIds = skillIds.toSet(),
    defaultMode = AgentMode.valueOf(defaultMode),
    allowedModes = allowedModes.map { AgentMode.valueOf(it) }.toSet(),
    memoryPolicy = AgentMemoryPolicy(namespace = memoryNamespace, includeSharedProfile = includeSharedProfile),
    allowedWidgetTemplates = allowedWidgetTemplates.toSet(),
    hooks = emptyList(),
    stateKeyPrefixes = emptySet(),
    configVersion = configVersion,
    status = AgentStatus.valueOf(status),
  )

  companion object {
    /** Builds the row to persist for [definition] — a fresh insert or the next version of an existing row. */
    fun fromDefinition(definition: AgentDefinition, createdAt: Long, updatedAt: Long): UserAgentEntity = UserAgentEntity(
      id = definition.id,
      name = definition.name,
      description = definition.description,
      iconKey = definition.iconKey,
      systemPrompt = definition.systemPrompt,
      toolNames = definition.toolNames.toList(),
      skillIds = definition.skillIds.toList(),
      allowedModes = definition.allowedModes.map { it.name },
      allowedWidgetTemplates = definition.allowedWidgetTemplates.toList(),
      defaultMode = definition.defaultMode.name,
      memoryNamespace = definition.memoryPolicy.namespace,
      includeSharedProfile = definition.memoryPolicy.includeSharedProfile,
      status = definition.status.name,
      configVersion = definition.configVersion,
      createdAt = createdAt,
      updatedAt = updatedAt,
    )
  }
}
