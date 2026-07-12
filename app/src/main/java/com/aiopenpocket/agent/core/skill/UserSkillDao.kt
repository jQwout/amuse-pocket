package com.aiopenpocket.agent.core.skill

// @trace user-agent-builder-848153

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Room access for [UserSkillEntity] rows. [UserSkillRepository] (read side, consumed by
 * [SkillRegistry]) and [com.aiopenpocket.agent.core.agent.UserAgentRepository] (write side, inside
 * its publish transaction) are the only callers — mirrors how
 * [com.aiopenpocket.agent.core.agent.UserAgentDao] only ever moves rows in and out of SQLite,
 * leaving validation/mapping to its callers.
 */
@Dao
interface UserSkillDao {
  @Query("SELECT * FROM user_skills WHERE id = :id")
  suspend fun get(id: String): UserSkillEntity?

  @Query("SELECT * FROM user_skills")
  suspend fun getAll(): List<UserSkillEntity>

  @Query("SELECT * FROM user_skills WHERE agentId = :agentId")
  suspend fun getByAgent(agentId: String): List<UserSkillEntity>

  @Upsert
  suspend fun upsert(entity: UserSkillEntity)
}
