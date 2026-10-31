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
  /**
   * [AgentMode.name] strings, plus at most one `goalMaxRounds=N` entry: see [AgentDefinition.goalMaxRounds]
   * and [GOAL_ROUNDS_PREFIX] for why the limit rides in this list rather than a column of its own.
   */
  val allowedModes: List<String>,
  val allowedWidgetTemplates: List<String>,
  /**
   * See [AgentDefinition.stateKeyPrefixes]. Stored since schema v10: without a column, every USER
   * agent reported an empty set, so a clone of a preset that relies on state injection (the
   * "languages" tutor's `goal:`/`lang:`/`grammar:`) came out subtly broken — the copy stopped
   * getting its own persistent state in the prompt, with nothing in the UI saying so.
   */
  val stateKeyPrefixes: List<String>,
  /** See [AgentDefinition.model]. Nullable column: null = the app's model. Added in schema v12. */
  val model: String?,
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
    allowedModes = allowedModes.filterNot { it.startsWith(GOAL_ROUNDS_PREFIX) }.map { AgentMode.valueOf(it) }.toSet(),
    memoryPolicy = AgentMemoryPolicy(namespace = memoryNamespace, includeSharedProfile = includeSharedProfile),
    allowedWidgetTemplates = allowedWidgetTemplates.toSet(),
    hooks = emptyList(),
    stateKeyPrefixes = stateKeyPrefixes.toSet(),
    model = model,
    goalMaxRounds = allowedModes.firstNotNullOfOrNull { entry ->
      entry.takeIf { it.startsWith(GOAL_ROUNDS_PREFIX) }?.removePrefix(GOAL_ROUNDS_PREFIX)?.toIntOrNull()
    },
    configVersion = configVersion,
    status = AgentStatus.valueOf(status),
  )

  companion object {
    /**
     * Marks the goal-round limit inside [allowedModes]. The limit is stored in the JSON list that column
     * already holds, not in a new column, because a Room schema change here means a version bump, and
     * until the first release a bump is a destructive fallback: every agent, chat and plan on the owner's
     * phone is wiped for one small number. A limit only means something next to the GOAL mode it limits,
     * so it belongs in that list. Revisit when the first release makes real migrations the policy.
     */
    const val GOAL_ROUNDS_PREFIX = "goalMaxRounds="

    /** Builds the row to persist for [definition] — a fresh insert or the next version of an existing row. */
    fun fromDefinition(definition: AgentDefinition, createdAt: Long, updatedAt: Long): UserAgentEntity = UserAgentEntity(
      id = definition.id,
      name = definition.name,
      description = definition.description,
      iconKey = definition.iconKey,
      systemPrompt = definition.systemPrompt,
      toolNames = definition.toolNames.toList(),
      skillIds = definition.skillIds.toList(),
      allowedModes = definition.allowedModes.map { it.name } +
        listOfNotNull(definition.goalMaxRounds?.let { "$GOAL_ROUNDS_PREFIX$it" }),
      allowedWidgetTemplates = definition.allowedWidgetTemplates.toList(),
      stateKeyPrefixes = definition.stateKeyPrefixes.toList(),
      model = definition.model,
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
