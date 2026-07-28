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

  /**
   * Catalog/ids projection: the same rows as [getAll], minus [UserSkillEntity.instructionsMarkdown]
   * (up to [com.aiopenpocket.agent.core.agent.AgentValidator.MAX_CUSTOM_SKILL_CHARS] chars per
   * skill). [SkillRegistry.ids] and [SkillRegistry.catalog] only ever read manifest-shaped fields,
   * yet went through [getAll] — i.e. every custom skill's full markdown body, on EVERY chat turn via
   * [com.aiopenpocket.agent.core.prompt.PrePromptAssembler]. [get] above still returns the full row
   * for the one caller that actually needs the body ([UserSkillRepository.get], for `load_skill`).
   */
  @Query("SELECT id, name, description, whenToUse FROM user_skills")
  suspend fun getAllMetadata(): List<UserSkillMetadata>

  @Query("SELECT * FROM user_skills WHERE agentId = :agentId")
  suspend fun getByAgent(agentId: String): List<UserSkillEntity>

  @Upsert
  suspend fun upsert(entity: UserSkillEntity)

  /**
   * Delete half of the wholesale replace both
   * [com.aiopenpocket.agent.core.agent.UserAgentRepository.saveDraft] and `publish` do inside their
   * own transaction (mirrors [com.aiopenpocket.agent.core.hook.AgentHookDao.deleteForAgent]): a
   * custom skill the user removed in the wizard has to actually leave `user_skills`, or
   * [SkillRegistry] keeps offering it to the model forever.
   */
  @Query("DELETE FROM user_skills WHERE agentId = :agentId")
  suspend fun deleteForAgent(agentId: String)
}
