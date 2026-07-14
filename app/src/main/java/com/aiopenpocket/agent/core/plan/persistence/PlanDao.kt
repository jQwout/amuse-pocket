package com.aiopenpocket.agent.core.plan.persistence

// @trace structured-plan-engine-907066

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the five Structured Plan Engine tables (design spec
 * `2026-07-12-structured-plan-engine-design.md` section 6). [PlanRepository] is the only caller —
 * mirrors [com.aiopenpocket.agent.core.hook.AgentHookDao]/[com.aiopenpocket.agent.core.agent.UserAgentDao]:
 * this Dao only moves rows in and out of SQLite. State-machine gating, immutability enforcement, the
 * "approved-or-latest version" read policy and the one-active-execution-per-conversation guard all live
 * in [PlanRepository], never here.
 */
@Dao
interface PlanDao {

  // --- agent_plans --------------------------------------------------------------------------------

  @Query("SELECT * FROM agent_plans WHERE id = :planId")
  suspend fun getPlan(planId: String): AgentPlanEntity?

  @Query("SELECT * FROM agent_plans WHERE id = :planId")
  fun observePlan(planId: String): Flow<AgentPlanEntity?>

  @Query("SELECT * FROM agent_plans WHERE conversationId = :conversationId ORDER BY updatedAt DESC")
  fun observePlansByConversation(conversationId: String): Flow<List<AgentPlanEntity>>

  @Query("SELECT * FROM agent_plans WHERE agentId = :agentId ORDER BY updatedAt DESC")
  fun observePlansByAgent(agentId: String): Flow<List<AgentPlanEntity>>

  /** [PlanRepository.approve]'s "one active execution per conversation" guard (design spec sections 2, 10). */
  @Query(
    "SELECT COUNT(*) FROM agent_plans WHERE conversationId = :conversationId AND status = 'ACTIVE' AND id != :excludePlanId",
  )
  suspend fun countOtherActiveInConversation(conversationId: String, excludePlanId: String): Int

  @Upsert
  suspend fun upsertPlan(entity: AgentPlanEntity)

  /**
   * Bumps only `updatedAt`. Called whenever progress/evidence/an event changes so [observePlan]/
   * [observePlansByConversation]/[observePlansByAgent] — Room Flow queries invalidate per TABLE, not
   * per row/column — actually re-emit for a change that otherwise touches only `plan_step_states`/
   * `plan_evidence`/`plan_events`, mirroring [com.aiopenpocket.agent.core.memory.dao.ConversationDao.touch]
   * bumping a conversation's `updatedAt` when a message is added.
   */
  @Query("UPDATE agent_plans SET updatedAt = :updatedAt WHERE id = :planId")
  suspend fun touchPlan(planId: String, updatedAt: Long)

  // --- plan_versions -------------------------------------------------------------------------------

  @Query("SELECT * FROM plan_versions WHERE planId = :planId AND version = :version")
  suspend fun getVersion(planId: String, version: Int): PlanVersionEntity?

  /** Null iff [planId] has no version row at all (never true for a plan created only via [PlanRepository.saveProposal], which always writes plan+version together). */
  @Query("SELECT MAX(version) FROM plan_versions WHERE planId = :planId")
  suspend fun getMaxVersion(planId: String): Int?

  /** Full version history, oldest first — [PlanRepository.getVersions]. */
  @Query("SELECT * FROM plan_versions WHERE planId = :planId ORDER BY version ASC")
  suspend fun getVersionsForPlan(planId: String): List<PlanVersionEntity>

  @Upsert
  suspend fun upsertVersion(entity: PlanVersionEntity)

  // --- plan_step_states ----------------------------------------------------------------------------

  @Query("SELECT * FROM plan_step_states WHERE planId = :planId AND version = :version")
  suspend fun getStepStates(planId: String, version: Int): List<PlanStepStateEntity>

  @Upsert
  suspend fun upsertStepState(entity: PlanStepStateEntity)

  // --- plan_evidence --------------------------------------------------------------------------------

  @Query("SELECT * FROM plan_evidence WHERE planId = :planId AND version = :version ORDER BY createdAt ASC")
  suspend fun getEvidence(planId: String, version: Int): List<PlanEvidenceEntity>

  /** Evidence is append-mostly, keyed by a caller-supplied UUID — REPLACE mirrors [com.aiopenpocket.agent.core.hook.AgentHookDao.insertRun]'s journal-row precedent (idempotent retry-safe). */
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEvidence(entity: PlanEvidenceEntity)

  // --- plan_events ----------------------------------------------------------------------------------

  @Query("SELECT * FROM plan_events WHERE planId = :planId ORDER BY createdAt ASC")
  suspend fun getEvents(planId: String): List<PlanEventEntity>

  @Query("SELECT * FROM plan_events WHERE planId = :planId ORDER BY createdAt ASC")
  fun observeEvents(planId: String): Flow<List<PlanEventEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertEvent(entity: PlanEventEntity)
}
