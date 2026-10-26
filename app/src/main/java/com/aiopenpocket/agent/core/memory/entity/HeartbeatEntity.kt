package com.aiopenpocket.agent.core.memory.entity

// @trace heartbeat-napominalki-agenta-5f0442
// @trace structured-plan-engine-907066

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Which kind of thing a [HeartbeatEntity] wakes up (Task 11 of the Structured Plan Engine, design
 * spec `2026-07-12-structured-plan-engine-design.md` section 12.1). [GENERIC_AGENT] is every
 * heartbeat that existed before this task — a free-text `mode`=push/agent reminder, dispatched by
 * [com.aiopenpocket.agent.workers.HeartbeatRunner] exactly as before. [PLAN_STEP] carries a typed
 * [com.aiopenpocket.agent.core.plan.model.AgentPlan]/[com.aiopenpocket.agent.core.plan.model.PlanStep]
 * reference (see [HeartbeatEntity.planId]/[HeartbeatEntity.planVersion]/[HeartbeatEntity.planStepId])
 * instead of a free-text prompt — a free-text heartbeat prompt is NEVER treated as a plan reference.
 * Only [com.aiopenpocket.agent.core.plan.schedule.PlanScheduler] ever creates a [PLAN_STEP] row; the
 * LLM-facing `heartbeat` tool never accepts a `planId`/`planVersion`/`stepId` argument.
 */
enum class HeartbeatTargetType { GENERIC_AGENT, PLAN_STEP }

/**
 * The registry of reminders/heartbeats — the source of truth for the card in the chat and for
 * [com.aiopenpocket.agent.workers.HeartbeatWorker]. Only the id goes to WorkManager (working around the
 * 10KB input Data limit); unique work name = "heartbeat_<id>".
 */
@Entity(tableName = "heartbeats")
data class HeartbeatEntity(
  @PrimaryKey val id: String,
  val title: String,
  /** "push" — a static push without the LLM; "agent" — a background wake-up of the agent. Ignored when [targetType]
   * is [HeartbeatTargetType.PLAN_STEP] — that path is dispatched purely off [targetType], never `mode`. */
  val mode: String,
  /** The task for agent mode; null = the default "pulse" (the agent decides itself whether to write). Unused for a
   * [HeartbeatTargetType.PLAN_STEP] row — [com.aiopenpocket.agent.core.plan.execution.PlanExecutor]
   * builds that run's whole prompt from persisted plan data, never from this field. */
  val prompt: String? = null,
  /** The push text for push mode. */
  val message: String? = null,
  /** The mini-agent on whose behalf we wake up; null = the default assistant. For a [HeartbeatTargetType.PLAN_STEP]
   * row this mirrors [com.aiopenpocket.agent.core.plan.model.AgentPlan.agentId], not a value the LLM chose. */
  val agentId: String? = null,
  /** The conversation that agent mode writes its result to (and that a tap on the push opens). */
  val conversationId: String,
  /** "one_time" | "periodic". */
  val kind: String,
  /** Epoch millis of the scheduled firing. For "one_time" this is the sole fire time; for "periodic"
   * this is the FIRST occurrence only (mirrors the original `firstRunDelay = delay ?: interval`
   * behavior `HeartbeatTool.schedule` had before Task 11 moved WorkManager mechanics out to
   * [com.aiopenpocket.agent.core.plan.schedule.HeartbeatScheduler]) — later periodic fires are timed
   * by WorkManager itself off [intervalMinutes]. */
  val fireAt: Long? = null,
  val intervalMinutes: Long? = null,
  /** "scheduled" | "fired" | "cancelled" | "error". A periodic one stays scheduled after firing. */
  val status: String = "scheduled",
  val createdAt: Long = System.currentTimeMillis(),
  val lastRunAt: Long? = null,
  /** A short digest of the last result — for the card. */
  val lastResult: String? = null,
  /** [HeartbeatTargetType.GENERIC_AGENT] (default) for every pre-Task-11 row and every ordinary
   * push/agent reminder created via the `heartbeat` tool; [HeartbeatTargetType.PLAN_STEP] only for a
   * row [com.aiopenpocket.agent.core.plan.schedule.PlanScheduler] created. Stored as the enum's plain
   * `.name` — mirrors how [mode]/[kind]/[status] are already plain strings on this same entity, and
   * how `AgentPlanEntity.status`/`PlanStepStateEntity.status` store [com.aiopenpocket.agent.core.plan.model.PlanStatus]/
   * [com.aiopenpocket.agent.core.plan.model.StepStatus] the same way — never a real Room-converted enum column. */
  val targetType: String = "GENERIC_AGENT",
  /** [com.aiopenpocket.agent.core.plan.model.AgentPlan.id] this heartbeat resumes; non-null only when [targetType] is [HeartbeatTargetType.PLAN_STEP]. */
  val planId: String? = null,
  /** The plan's approved version this heartbeat was armed against. A heartbeat whose plan has since
   * been approved to a DIFFERENT version is stale (design spec section 12.1: "the heartbeat does not run
   * the old step") — [com.aiopenpocket.agent.workers.HeartbeatRunner] checks this against the plan's
   * CURRENT `approvedVersion` before ever running the step, never trusts this field blindly. */
  val planVersion: Int? = null,
  /** [com.aiopenpocket.agent.core.plan.model.PlanStep.id] this heartbeat resumes. */
  val planStepId: String? = null,
)
