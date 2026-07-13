package com.aiopenpocket.agent.core.agent

// @trace user-agent-builder-848153

import androidx.room.withTransaction
import com.aiopenpocket.agent.core.hook.AgentHook
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.skill.UserSkillEntity
import com.aiopenpocket.agent.core.skill.validateUserSkill
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Transactional Room-backed store for [AgentSource.USER] agent drafts/published/archived agents.
 * Presets never pass through here — they stay in the read-only [MiniAgentCatalog] — so every
 * [AgentDefinition] this repository hands back has [AgentDefinition.source] == [AgentSource.USER].
 *
 * [clock] defaults to the wall clock. It intentionally is not a parameter of the `@Inject`
 * constructor Metro resolves (a bare `() -> Long` binding would be untyped and ambiguous across the
 * whole app graph — see how `TurnManager`'s `monotonicNowMs` is wired by hand in `TurnModule`
 * instead of via `@Inject`, for the same reason). The secondary constructor below lets tests
 * substitute a fixed time source for deterministic createdAt/updatedAt assertions without changing
 * what Metro sees.
 */
@SingleIn(AppScope::class)
class UserAgentRepository @Inject constructor(
  private val database: AppDatabase,
  private val dao: UserAgentDao,
  private val hookRepository: AgentHookRepository,
) {
  private var clock: () -> Long = System::currentTimeMillis

  constructor(
    database: AppDatabase,
    dao: UserAgentDao,
    hookRepository: AgentHookRepository,
    clock: () -> Long,
  ) : this(database, dao, hookRepository) {
    this.clock = clock
  }

  /** Published USER agents only, most-recently-updated first — what the roster shows. */
  fun observePublished(): Flow<List<AgentDefinition>> =
    dao.observePublished().map { rows -> rows.map { it.toDefinition().withHooks() } }

  suspend fun get(id: String): AgentDefinition? = dao.get(id)?.toDefinition()?.withHooks()

  /** Attaches this agent's currently-persisted [AgentHook]s (Task 8) — [UserAgentEntity.toDefinition] itself always returns `hooks = emptyList()`, since hooks live in their own table, not a column on this entity. */
  private suspend fun AgentDefinition.withHooks(): AgentDefinition = copy(hooks = hookRepository.getForAgent(id))

  /**
   * Upserts [definition] as a DRAFT at configVersion 0, regardless of the status/configVersion the
   * caller passed in — [publish] is the only path that ever advances past DRAFT/0. Preserves the
   * original row's `createdAt` across repeated edits of the same id; only `updatedAt` moves.
   */
  suspend fun saveDraft(definition: AgentDefinition): AgentDefinition {
    val now = clock()
    val existing = dao.get(definition.id)
    val entity = UserAgentEntity.fromDefinition(
      definition = definition.copy(status = AgentStatus.DRAFT, configVersion = 0L),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsert(entity)
    return entity.toDefinition().withHooks()
  }

  /**
   * Validates then publishes [id]'s current row, bumping [AgentDefinition.configVersion] by one,
   * together with its [skills] (Task 4's `user_skills` — pass the agent's full current set of
   * custom skill drafts; each must have [UserSkillEntity.agentId] == [id]) and its [hooks] (Task 8's
   * `agent_hooks` — pass the agent's full current hook set; each must have [AgentHook.agentId] ==
   * [id]). Wrapped in [withTransaction] — not the Room `@Transaction` annotation, which only has an
   * effect on `@Dao` methods — so the validate-then-write stays atomic across all three tables.
   * Throws and leaves the stored agent row, every stored skill row AND every stored hook row
   * untouched when the agent definition ([AgentValidator.requirePublishable] — this now also checks
   * [hooks] against the action/event matrix, since they're attached to the definition being
   * validated), any skill ([validateUserSkill]: blank/oversized markdown, id colliding with a
   * built-in), a passed skill's/hook's `agentId` not matching [id], or a skill/hook id already
   * belonging to a DIFFERENT agent (would otherwise silently reassign ownership via upsert) fails —
   * validation runs strictly before any write, so a rejected publish never partially applies.
   */
  suspend fun publish(
    id: String,
    skills: List<UserSkillEntity> = emptyList(),
    hooks: List<AgentHook> = emptyList(),
  ): AgentDefinition = database.withTransaction {
    val current = requireNotNull(dao.get(id)) { "Unknown agent id: $id" }
    AgentValidator.requirePublishable(
      current.toDefinition().copy(hooks = hooks),
      ownedCustomSkillIds = skills.map { it.id }.toSet(),
    )

    val skillDao = database.userSkillDao()
    val skillIssues = mutableListOf<ValidationIssue>()
    for (skill in skills) {
      require(skill.agentId == id) { "Skill '${skill.id}' does not belong to agent '$id'" }
      skillIssues += validateUserSkill(skill)
      val existingOwner = skillDao.get(skill.id)?.agentId
      if (existingOwner != null && existingOwner != id) {
        skillIssues += ValidationIssue(
          code = "skill_owned_by_another_agent",
          message = "Навык '${skill.id}' уже принадлежит другому агенту",
          field = "id",
        )
      }
    }
    require(skillIssues.isEmpty()) {
      "Agent '$id' custom skills are not publishable: " +
        skillIssues.joinToString("; ") { "${it.code} (${it.field}): ${it.message}" }
    }

    val hookIssues = mutableListOf<ValidationIssue>()
    for (hook in hooks) {
      require(hook.agentId == id) { "Hook '${hook.id}' does not belong to agent '$id'" }
      val existingOwner = hookRepository.getOwnerAgentId(hook.id)
      if (existingOwner != null && existingOwner != id) {
        hookIssues += ValidationIssue(
          code = "hook_owned_by_another_agent",
          message = "Хук '${hook.id}' уже принадлежит другому агенту",
          field = "id",
        )
      }
    }
    require(hookIssues.isEmpty()) {
      "Agent '$id' hooks are not publishable: " +
        hookIssues.joinToString("; ") { "${it.code} (${it.field}): ${it.message}" }
    }

    val published = current.copy(
      status = AgentStatus.PUBLISHED.name,
      configVersion = current.configVersion + 1,
      updatedAt = clock(),
    )
    dao.upsert(published)
    skills.forEach { skillDao.upsert(it) }
    hookRepository.replaceForAgent(id, hooks)
    published.toDefinition().withHooks()
  }

  /** Archives [id] in place — the row survives (per the [AgentStatus.ARCHIVED] contract), just drops out of [observePublished]. */
  suspend fun archive(id: String) {
    requireNotNull(dao.get(id)) { "Unknown agent id: $id" }
    dao.updateStatus(id, AgentStatus.ARCHIVED.name, clock())
  }

  /** Forks a read-only preset's [AgentDefinition] into a brand new, independently editable USER draft. */
  suspend fun clonePreset(preset: AgentDefinition): AgentDefinition {
    val newId = "${preset.id}-${UUID.randomUUID().toString().take(8)}"
    val cloned = preset.copy(
      id = newId,
      source = AgentSource.USER,
      status = AgentStatus.DRAFT,
      configVersion = 0L,
      memoryPolicy = preset.memoryPolicy.copy(namespace = "agent:$newId:"),
    )
    return saveDraft(cloned)
  }
}
