package com.aiopenpocket.agent.core.hook

// @trace user-agent-builder-848153

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for one [AgentHook] — mirrors
 * [com.aiopenpocket.agent.core.agent.UserAgentEntity] being the Room counterpart to its own domain
 * type. Every column is a primitive/String (no `List<String>` fields like [AgentHook] has none of
 * its own collection-shaped properties either) so this needs no [com.aiopenpocket.agent.core.agent.AgentRoomConverters]
 * entry, unlike the agent entity.
 *
 * Owned exclusively by [AgentHookRepository]: [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish]
 * writes rows through the repository (never this DAO directly) inside its own transaction, so a
 * definition's hooks and its `PUBLISHED` row land or roll back together.
 */
@Entity(tableName = "agent_hooks")
data class AgentHookEntity(
  @PrimaryKey val id: String,
  val agentId: String,
  val event: String,
  val conditionType: String,
  val conditionJson: String,
  val actionType: String,
  val actionJson: String,
  val priority: Int,
  val enabled: Boolean,
) {
  fun toDomain(): AgentHook = AgentHook(
    id = id,
    agentId = agentId,
    event = HookEvent.valueOf(event),
    conditionType = conditionType,
    conditionJson = conditionJson,
    actionType = HookActionType.valueOf(actionType),
    actionJson = actionJson,
    priority = priority,
    enabled = enabled,
  )

  companion object {
    fun fromDomain(hook: AgentHook): AgentHookEntity = AgentHookEntity(
      id = hook.id,
      agentId = hook.agentId,
      event = hook.event.name,
      conditionType = hook.conditionType,
      conditionJson = hook.conditionJson,
      actionType = hook.actionType.name,
      actionJson = hook.actionJson,
      priority = hook.priority,
      enabled = hook.enabled,
    )
  }
}

/**
 * Room row for one journaled hook firing — the audit trail [com.aiopenpocket.agent.loop.ReactLoopRunner]
 * writes (via [AgentHookRepository.recordRun]) for every [HookEngine.Firing] it applies, consumed
 * by the future agent inspector (Task 21 per the plan). [status] is a [HookActionType.name] (what the
 * hook actually did); [summary] is [HookEngine.Firing.detail] (empty for payload-less actions);
 * [errorMessage] is [HookEngine.Firing.error] when the hook's `actionJson` failed to parse — the hook
 * still fired (and still counted against the per-run budget), just with no usable payload.
 *
 * Capped to the most recent 100 rows per [hookId] — see [AgentHookRepository.recordRun] — so a hook
 * that fires on every turn of a long-lived agent can't grow this table unbounded.
 */
@Entity(tableName = "agent_hook_runs")
data class AgentHookRunEntity(
  @PrimaryKey val id: String,
  val hookId: String,
  val conversationId: String,
  val runId: String,
  val timestamp: Long,
  val status: String,
  val summary: String,
  val errorMessage: String?,
)
