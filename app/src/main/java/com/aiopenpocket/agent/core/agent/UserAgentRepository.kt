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
  fun observePublished(): Flow<List<AgentDefinition>> = dao.observePublished().map { it.decode() }

  /**
   * Never-published USER drafts, most-recently-updated first — the roster's "Drafts" section.
   * Without this read a draft was written by every autosave and then shown by nothing: the only
   * listing surface ([observePublished]) filters drafts out by design, so an agent whose wizard was
   * closed before "Publish" stayed in Room forever with no way back into it.
   */
  fun observeDrafts(): Flow<List<AgentDefinition>> = dao.observeDrafts().map { it.decode() }

  /**
   * Quarantine an agent row that no longer decodes (schema drift with no Room migration — a renamed
   * enum) instead of throwing: one bad row must not crash a roster Flow AgentsViewModel collects, or
   * the agents screen crash-loops on every open. Decode is wrapped in runCatching (pure, no suspend
   * inside); withHooks (suspend) runs only on the survivors.
   */
  private suspend fun List<UserAgentEntity>.decode(): List<AgentDefinition> =
    mapNotNull { row -> runCatching { row.toDefinition() }.getOrNull()?.withHooks() }

  suspend fun get(id: String): AgentDefinition? =
    dao.get(id)?.let { runCatching { it.toDefinition() }.getOrNull() }?.withHooks()

  /** Attaches this agent's currently-persisted [AgentHook]s (Task 8) — [UserAgentEntity.toDefinition] itself always returns `hooks = emptyList()`, since hooks live in their own table, not a column on this entity. */
  private suspend fun AgentDefinition.withHooks(): AgentDefinition = copy(hooks = hookRepository.getForAgent(id))

  /**
   * Persists [definition] as the agent's current stored state, together with its [hooks] and custom
   * [skills] — the wizard's autosave path. Preserves the original row's `createdAt` across repeated
   * edits of the same id; only `updatedAt` moves.
   *
   * ### Status/configVersion are PRESERVED, not reset
   *
   * This used to force `status = DRAFT, configVersion = 0` on every call. Chained with the builder's
   * per-keystroke autosave that meant editing a PUBLISHED agent silently unpublished it: it dropped
   * out of [observePublished] (i.e. out of "My agents") the moment the user typed one character, and
   * closing the wizard without pressing "Publish" left it invisible — recoverable only through an
   * already-existing chat with it. It also forced `publish` to be called in a catch-up LOOP to walk a
   * reset configVersion back up to its true next value.
   *
   * Now an existing row keeps whatever [AgentStatus]/`configVersion` it already had, so:
   * - a never-published draft stays [AgentStatus.DRAFT] at 0 (and is listed by [observeDrafts]);
   * - a published agent stays published and keeps its version — its edits go live for the NEXT run
   *   (every run takes an immutable snapshot, so nothing in flight changes), and "Publish"
   *   becomes the explicit "cut a new configVersion" action rather than the only way back into the
   *   roster;
   * - an archived agent stays archived until an explicit [publish] revives it.
   *
   * [hooks] and [skills] are written here too (wholesale replace, scoped to this agent) rather than
   * only inside [publish]: hooks/custom skills authored in the wizard used to exist purely in memory
   * until publish, so closing the wizard first threw them away. Rows owned by a DIFFERENT agent are
   * skipped rather than silently stolen — [publish] still hard-fails on that case, this path just
   * refuses to make it worse mid-edit.
   */
  suspend fun saveDraft(
    definition: AgentDefinition,
    skills: List<UserSkillEntity> = emptyList(),
    hooks: List<AgentHook> = definition.hooks,
  ): AgentDefinition = database.withTransaction {
    val now = clock()
    val existing = dao.get(definition.id)
    val storedStatus = existing?.let { runCatching { AgentStatus.valueOf(it.status) }.getOrNull() }
    val entity = UserAgentEntity.fromDefinition(
      definition = definition.copy(
        status = storedStatus ?: AgentStatus.DRAFT,
        configVersion = existing?.configVersion ?: 0L,
      ),
      createdAt = existing?.createdAt ?: now,
      updatedAt = now,
    )
    dao.upsert(entity)

    val skillDao = database.userSkillDao()
    val ownSkills = skills.filter { skill ->
      skill.agentId == definition.id && skillDao.get(skill.id)?.agentId.let { it == null || it == definition.id }
    }
    val skillCreatedAt = ownSkills.associate { it.id to (skillDao.get(it.id)?.createdAt ?: now) }
    skillDao.deleteForAgent(definition.id)
    ownSkills.forEach { skillDao.upsert(it.stamped(skillCreatedAt.getValue(it.id), now)) }

    val ownHooks = hooks.filter { hook ->
      hook.agentId == definition.id && hookRepository.getOwnerAgentId(hook.id).let { it == null || it == definition.id }
    }
    hookRepository.replaceForAgent(definition.id, ownHooks)

    entity.toDefinition().withHooks()
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
    // Wholesale replace, matching hooks' own replaceForAgent semantics (and saveDraft's): a plain
    // per-row upsert left a custom skill the user DELETED in the wizard alive in Room, so
    // SkillRegistry kept advertising it in the prompt catalog after it was gone from the agent.
    val now = clock()
    val skillCreatedAt = skills.associate { it.id to (skillDao.get(it.id)?.createdAt ?: now) }
    skillDao.deleteForAgent(id)
    skills.forEach { skillDao.upsert(it.stamped(skillCreatedAt.getValue(it.id), now)) }
    hookRepository.replaceForAgent(id, hooks)
    published.toDefinition().withHooks()
  }

  /**
   * Both write paths own the row's timestamps rather than trusting the caller's: the builder builds
   * [UserSkillEntity] values out of pure UI state and has no clock of its own (this repository's
   * [clock] is the one tests substitute), so it passes 0s and lets the write stamp them.
   */
  private fun UserSkillEntity.stamped(createdAt: Long, updatedAt: Long): UserSkillEntity =
    copy(createdAt = createdAt, updatedAt = updatedAt)

  /** Archives [id] in place — the row survives (per the [AgentStatus.ARCHIVED] contract), just drops out of [observePublished]. */
  suspend fun archive(id: String) {
    requireNotNull(dao.get(id)) { "Unknown agent id: $id" }
    dao.updateStatus(id, AgentStatus.ARCHIVED.name, clock())
  }

  /**
   * Demotes a published agent back to [AgentStatus.DRAFT] — the explicit "turn the agent off" action,
   * the mirror of [publish]. This used to be expressed as a bare [saveDraft] call, relying on that
   * method's old side effect of force-resetting status; now that saveDraft preserves status (so a
   * per-keystroke autosave can't silently unpublish anything), demoting has to say so out loud.
   * [configVersion] is deliberately left alone: the agent keeps the version it was published at, so
   * re-enabling it bumps to the next one rather than restarting the count.
   */
  suspend fun unpublish(id: String) {
    requireNotNull(dao.get(id)) { "Unknown agent id: $id" }
    dao.updateStatus(id, AgentStatus.DRAFT.name, clock())
  }

  /**
   * Forks any [AgentDefinition] — a read-only preset OR another user agent — into a brand new,
   * independently editable USER draft under a fresh id and memory namespace.
   *
   * Hooks and custom skills are forked too, under fresh ids owned by the copy. Without that, a fork
   * of a user agent carried `skillIds`/`hooks` still pointing at the SOURCE's rows: [saveDraft] and
   * [publish] both refuse to reassign another agent's rows, so the hooks silently vanished and every
   * copied custom skill id validated as `unknown_skill` — an error the wizard shows on "Capabilities"
   * with no control able to clear it, wedging the copy permanently. A `skillIds` entry that is
   * neither a built-in nor one of the source's own custom skills is dropped rather than copied
   * forward, for the same reason.
   */
  suspend fun clonePreset(source: AgentDefinition): AgentDefinition = database.withTransaction {
    val suffix = UUID.randomUUID().toString().take(8)
    val newId = "${source.id}-$suffix"
    val sourceSkills = database.userSkillDao().getByAgent(source.id)
    val skillIdMapping = sourceSkills.associate { it.id to "${it.id}-$suffix" }
    val now = clock()

    val clonedSkills = sourceSkills.map { skill ->
      skill.copy(
        id = skillIdMapping.getValue(skill.id),
        agentId = newId,
        createdAt = now,
        updatedAt = now,
      )
    }
    val clonedHooks = source.hooks.map { hook ->
      hook.copy(id = UUID.randomUUID().toString(), agentId = newId)
    }
    val cloned = source.copy(
      id = newId,
      source = AgentSource.USER,
      status = AgentStatus.DRAFT,
      configVersion = 0L,
      skillIds = source.skillIds.mapNotNullTo(mutableSetOf()) { id ->
        if (id in AgentValidator.KNOWN_SKILL_IDS) id else skillIdMapping[id]
      },
      memoryPolicy = source.memoryPolicy.copy(namespace = "agent:$newId:"),
      hooks = clonedHooks,
    )
    saveDraft(cloned, skills = clonedSkills, hooks = clonedHooks)
  }
}
