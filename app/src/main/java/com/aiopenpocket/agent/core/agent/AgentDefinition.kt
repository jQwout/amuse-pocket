package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.loop.AgentMode

/** Where an [AgentDefinition] came from: the read-only Kotlin preset catalog, or a Room-backed user agent. */
enum class AgentSource { PRESET, USER }

/**
 * Lifecycle state of a [AgentSource.USER] agent. Presets are always [PUBLISHED] (Task 1 adapter).
 * [DRAFT] agents never appear in the roster; deleting a published agent moves it to [ARCHIVED]
 * rather than removing the row (per the plan's Global Constraints).
 */
enum class AgentStatus { DRAFT, PUBLISHED, ARCHIVED }

/**
 * Per-agent memory isolation. [namespace] is the key prefix (`agent:$id:`, enforced by
 * [AgentValidator]) this agent's own facts/state are stored and read under; [includeSharedProfile]
 * controls whether the app-wide shared user profile is additionally merged into its context.
 */
data class AgentMemoryPolicy(
  val namespace: String,
  val includeSharedProfile: Boolean,
)

/**
 * Immutable runtime snapshot of one agent — preset or user-authored — that `AgentResolver`
 * (later tasks) hands to `PrePromptAssembler`/`ReactLoopRunner`/`ToolExecutor`. Nothing here is
 * mutated in place: publishing or editing a user agent produces a new [AgentDefinition] with a
 * bumped [configVersion], so an in-flight turn always sees a single consistent snapshot.
 *
 * [enabled] is the single place callers should ask "can this agent be used right now?" — it is
 * intentionally derived from [status] rather than stored, so there is exactly one way to flip it
 * (publish/archive) instead of two fields that could disagree.
 */
data class AgentDefinition(
  val id: String,
  val source: AgentSource,
  val name: String,
  val description: String,
  val iconKey: String,
  val systemPrompt: String,
  val toolNames: Set<String>,
  val skillIds: Set<String>,
  val defaultMode: AgentMode,
  val allowedModes: Set<AgentMode>,
  val memoryPolicy: AgentMemoryPolicy,
  val allowedWidgetTemplates: Set<String>,
  val hooks: List<AgentHook> = emptyList(),
  /**
   * Key prefixes whose facts get injected into the prompt as this agent's persistent state (see
   * `PrePromptAssembler`'s "agent state" section) — e.g. the languages preset's `goal:`/`lang:`/
   * `grammar:` prefixes. Always empty for [AgentSource.USER] agents in v1: no builder UI exists yet
   * to configure it, and [UserAgentEntity] has no column for it (see [UserAgentEntity.toDefinition]).
   */
  val stateKeyPrefixes: Set<String> = emptySet(),
  val configVersion: Long,
  val status: AgentStatus,
) {
  val enabled: Boolean get() = status == AgentStatus.PUBLISHED
}
