package com.aiopenpocket.agent.core.skill

// @trace user-agent-builder-848153

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.ValidationIssue

/**
 * Room row for one agent-owned custom skill — the second source [SkillRegistry] resolves
 * alongside the read-only built-in [Skill] beans from Metro (mirrors how
 * [com.aiopenpocket.agent.core.agent.UserAgentEntity] is the Room counterpart to the read-only
 * `MiniAgentCatalog` presets). A custom skill belongs to exactly one [agentId] — enforced by
 * [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish] at write time (via
 * [validateUserSkill] plus an explicit existing-owner check), not by a Room foreign key: agent and
 * skill rows are written in the same transaction, so a DB-level FK would only add ON DELETE
 * ceremony this v1 doesn't need yet. [id] doubles as the [SkillManifest.id] the model sees in the
 * skill catalog and passes to `load_skill(id)`.
 */
@Entity(tableName = "user_skills")
data class UserSkillEntity(
  @PrimaryKey val id: String,
  val agentId: String,
  val name: String,
  val description: String,
  val whenToUse: String,
  val instructionsMarkdown: String,
  val createdAt: Long,
  val updatedAt: Long,
) {
  /** Wraps this row as a plain [Skill] so [SkillRegistry] can treat it exactly like a built-in bean. */
  fun toSkill(): Skill {
    val entity = this
    return object : Skill {
      override val manifest = SkillManifest(id = entity.id, description = entity.description, whenToUse = entity.whenToUse)
      override suspend fun instructions(): String = entity.instructionsMarkdown
    }
  }
}

/**
 * Cheap projection of [UserSkillEntity] backing [UserSkillDao.getAllMetadata] — every column
 * [SkillRegistry.ids]/[SkillRegistry.catalog] actually read from a custom skill row, minus
 * [UserSkillEntity.instructionsMarkdown] (up to [AgentValidator.MAX_CUSTOM_SKILL_CHARS] chars,
 * pointless to load for a metadata-only catalog line). [name] isn't consumed by [toSkill] below —
 * kept for parity with the row's other non-markdown columns, so a future metadata-only caller (e.g.
 * a "my custom skills" list) doesn't need a second projection query.
 */
data class UserSkillMetadata(
  val id: String,
  val name: String,
  val description: String,
  val whenToUse: String,
) {
  /**
   * Wraps this row as a [Skill], mirroring [UserSkillEntity.toSkill] — except [Skill.instructions]
   * fetches the body lazily from [dao] on demand rather than returning an in-memory value, since this
   * projection never loaded [UserSkillEntity.instructionsMarkdown] in the first place. No current
   * caller of [UserSkillRepository.getAll] (only [SkillRegistry.catalog], which reads manifest
   * fields alone) invokes [Skill.instructions], but this keeps the contract honest instead of
   * silently returning an empty body if one ever does.
   */
  fun toSkill(dao: UserSkillDao): Skill {
    val meta = this
    return object : Skill {
      override val manifest = SkillManifest(id = meta.id, description = meta.description, whenToUse = meta.whenToUse)
      override suspend fun instructions(): String = dao.get(meta.id)?.instructionsMarkdown.orEmpty()
    }
  }
}

/**
 * v1 publish-time content rules for a custom skill. Kept out of [AgentValidator] (which stays pure
 * over [com.aiopenpocket.agent.core.agent.AgentDefinition] alone and never sees skill bodies —
 * `AgentDefinition.skillIds` is just ids) but mirrors its "collect every issue" style and reuses its
 * frozen [AgentValidator.MAX_CUSTOM_SKILL_CHARS] limit and [AgentValidator.KNOWN_SKILL_IDS] set.
 * Checked by [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish] before writing this
 * agent's custom skills alongside its published row; any issue rolls back the whole publish.
 */
fun validateUserSkill(entity: UserSkillEntity): List<ValidationIssue> {
  val issues = mutableListOf<ValidationIssue>()

  if (entity.instructionsMarkdown.isBlank()) {
    issues += ValidationIssue(
      code = "blank_skill_instructions",
      message = "Инструкция навыка '${entity.id}' не может быть пустой",
      field = "instructionsMarkdown",
    )
  } else if (entity.instructionsMarkdown.length > AgentValidator.MAX_CUSTOM_SKILL_CHARS) {
    issues += ValidationIssue(
      code = "skill_instructions_too_long",
      message = "Инструкция навыка '${entity.id}' длиннее ${AgentValidator.MAX_CUSTOM_SKILL_CHARS} символов",
      field = "instructionsMarkdown",
    )
  }

  // A custom skill sharing a built-in's id would always lose the SkillRegistry.get() collision
  // (built-in wins), making it a silently dead skill — reject it at publish time instead.
  if (entity.id in AgentValidator.KNOWN_SKILL_IDS) {
    issues += ValidationIssue(
      code = "skill_id_collision",
      message = "id навыка '${entity.id}' совпадает со встроенным навыком",
      field = "id",
    )
  }

  return issues
}
