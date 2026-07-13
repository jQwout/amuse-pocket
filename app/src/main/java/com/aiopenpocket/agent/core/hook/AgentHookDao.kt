package com.aiopenpocket.agent.core.hook

// @trace user-agent-builder-848153

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * Room access for [AgentHookEntity]/[AgentHookRunEntity] rows. [AgentHookRepository] is the only
 * caller — mirrors how [com.aiopenpocket.agent.core.agent.UserAgentDao] only ever moves rows in and
 * out of SQLite, leaving ordering/mapping/cleanup policy to its repository.
 */
@Dao
interface AgentHookDao {
  /** `(priority, id)` ascending — the same tie-break [HookEngine] itself sorts by, so any reader of this query already sees engine order. */
  @Query("SELECT * FROM agent_hooks WHERE agentId = :agentId ORDER BY priority ASC, id ASC")
  suspend fun getForAgent(agentId: String): List<AgentHookEntity>

  @Upsert
  suspend fun upsert(entity: AgentHookEntity)

  /** Whole-set replace target for [AgentHookRepository.replaceForAgent] — a hook list is edited/republished wholesale, not row-by-row. */
  @Query("DELETE FROM agent_hooks WHERE agentId = :agentId")
  suspend fun deleteForAgent(agentId: String)

  /** Who currently owns hook id [id], if anyone — [AgentHookRepository]'s publish-time cross-agent-collision guard (mirrors `UserSkillDao.get(id)?.agentId` for `user_skills`). */
  @Query("SELECT agentId FROM agent_hooks WHERE id = :id")
  suspend fun getOwnerAgentId(id: String): String?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertRun(run: AgentHookRunEntity)

  @Query("SELECT * FROM agent_hook_runs WHERE hookId = :hookId ORDER BY timestamp DESC")
  suspend fun getRunsForHook(hookId: String): List<AgentHookRunEntity>

  /** Keeps only the [keep] most recent runs for [hookId] — called after every insert, see [AgentHookRepository.recordRun]. */
  @Query(
    "DELETE FROM agent_hook_runs WHERE hookId = :hookId AND id NOT IN " +
      "(SELECT id FROM agent_hook_runs WHERE hookId = :hookId ORDER BY timestamp DESC LIMIT :keep)",
  )
  suspend fun trimRuns(hookId: String, keep: Int)
}
