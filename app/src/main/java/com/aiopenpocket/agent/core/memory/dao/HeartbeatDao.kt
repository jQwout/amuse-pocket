package com.aiopenpocket.agent.core.memory.dao

// @trace heartbeat-napominalki-agenta-5f0442
// @trace structured-plan-engine-907066

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartbeatDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(heartbeat: HeartbeatEntity)

  @Query("SELECT * FROM heartbeats WHERE id = :id")
  suspend fun getById(id: String): HeartbeatEntity?

  /** Живая карточка в чате подписывается на эту строку. */
  @Query("SELECT * FROM heartbeats WHERE id = :id")
  fun observe(id: String): Flow<HeartbeatEntity?>

  @Query("SELECT * FROM heartbeats WHERE status = 'scheduled' ORDER BY createdAt DESC")
  suspend fun listActive(): List<HeartbeatEntity>

  @Query("SELECT * FROM heartbeats WHERE agentId = :agentId AND status = 'scheduled' ORDER BY createdAt DESC")
  fun observeActiveByAgent(agentId: String): Flow<List<HeartbeatEntity>>

  /**
   * Every heartbeat currently targeting [planId] (any status except 'cancelled') — Task 11's
   * [com.aiopenpocket.agent.core.plan.schedule.PlanScheduler] reads this on every `sync`/`cancel` to
   * find the previously-armed row to reuse (same step/version still current) and any stale rows to
   * cancel (a superseded version, or a step that is no longer the next-due one). Not filtered to
   * `targetType = 'PLAN_STEP'` — a plan can never legitimately own a `GENERIC_AGENT` row, so this is
   * equivalent in practice, but filtering on `planId` alone keeps this query oblivious to that enum.
   */
  @Query("SELECT * FROM heartbeats WHERE planId = :planId AND status != 'cancelled'")
  suspend fun listForPlan(planId: String): List<HeartbeatEntity>

  /**
   * Every live (non-'cancelled') heartbeat tied to [conversationId] — plan-step AND generic agent
   * ones alike. Used to tear down a conversation's background schedule when it is deleted: heartbeats
   * have NO foreign key to `conversations` (an agent heartbeat can outlive/precede any one plan), so
   * the conversation-delete FK cascade never reaches them — without this cleanup a periodic WorkManager
   * job keeps firing forever against a conversation that no longer exists.
   */
  @Query("SELECT * FROM heartbeats WHERE conversationId = :conversationId AND status != 'cancelled'")
  suspend fun listForConversation(conversationId: String): List<HeartbeatEntity>

  @Query("UPDATE heartbeats SET status = :status WHERE id = :id")
  suspend fun setStatus(id: String, status: String)

  // Не воскрешаем отменённую запись: если cancel() выставил 'cancelled', пока agent-ход выполнялся,
  // record() по завершении не должен вернуть строку в 'scheduled'/'fired' (иначе фантом в списке
  // активных и вечное «Ждёт» на карточке при уже отменённой WorkManager-задаче).
  @Query(
    "UPDATE heartbeats SET status = :status, lastRunAt = :lastRunAt, lastResult = :lastResult " +
      "WHERE id = :id AND status != 'cancelled'",
  )
  suspend fun recordRun(id: String, status: String, lastRunAt: Long, lastResult: String?)
}
