package com.aiopenpocket.agent.core.skill

// @trace user-agent-builder-848153

import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Read-side second source for the composite [SkillRegistry]: agent-owned custom skills persisted
 * in Room. Mirrors how [com.aiopenpocket.agent.core.agent.AgentResolver] only ever READS
 * [com.aiopenpocket.agent.core.agent.UserAgentRepository] — the write path (upsert alongside an
 * agent's publish) goes directly through [UserSkillDao] from
 * [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish], not through this class, so this
 * repository stays a plain lookup surface for [SkillRegistry].
 */
@SingleIn(AppScope::class)
class UserSkillRepository @Inject constructor(
  private val dao: UserSkillDao,
) {
  suspend fun get(id: String): Skill? = dao.get(id)?.toSkill()

  suspend fun ids(): Set<String> = dao.getAll().mapTo(mutableSetOf()) { it.id }

  /** Every custom skill as a [Skill] — [SkillRegistry.catalog] dedups against built-ins itself. */
  suspend fun getAll(): List<Skill> = dao.getAll().map { it.toSkill() }
}
