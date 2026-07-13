package com.aiopenpocket.agent.core.hook

// @trace user-agent-builder-848153

import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Room-backed store for one agent's [AgentHook]s plus the `agent_hook_runs` firing journal.
 * [com.aiopenpocket.agent.core.agent.UserAgentRepository] is the only writer of the hook SET
 * ([replaceForAgent], called inside its own `publish` transaction — mirrors how
 * `UserSkillDao.upsert` is only ever called from that same transaction); [com.aiopenpocket.agent.loop.ReactLoopRunner]
 * is the only writer of the journal ([recordRun], once per [HookEngine.Firing] it applies).
 */
@SingleIn(AppScope::class)
class AgentHookRepository @Inject constructor(
  private val dao: AgentHookDao,
) {
  /** Every hook owned by [agentId], already sorted `(priority, id)` — the same order [HookEngine] itself applies. */
  suspend fun getForAgent(agentId: String): List<AgentHook> = dao.getForAgent(agentId).map { it.toDomain() }

  /**
   * Wholesale replace: deletes every existing row for [agentId] then inserts [hooks]. Called only
   * from inside [com.aiopenpocket.agent.core.agent.UserAgentRepository.publish]'s
   * `database.withTransaction { ... }` block, so a hook set and the agent's bumped `configVersion`
   * land or roll back together — never delete-without-reinsert on a validation failure partway
   * through the caller's transaction.
   */
  suspend fun replaceForAgent(agentId: String, hooks: List<AgentHook>) {
    dao.deleteForAgent(agentId)
    hooks.forEach { dao.upsert(AgentHookEntity.fromDomain(it)) }
  }

  /** Current owner of hook id [hookId], if the row exists — null if no hook has ever used this id. */
  suspend fun getOwnerAgentId(hookId: String): String? = dao.getOwnerAgentId(hookId)

  /** Journals one hook firing, then trims that hook's history back down to [KEEP_RUNS] rows. */
  suspend fun recordRun(run: AgentHookRunEntity) {
    dao.insertRun(run)
    dao.trimRuns(run.hookId, KEEP_RUNS)
  }

  /** Most recent firings first — read side for the future agent inspector journal (Task 21). */
  suspend fun getRunsForHook(hookId: String): List<AgentHookRunEntity> = dao.getRunsForHook(hookId)

  companion object {
    const val KEEP_RUNS = 100
  }
}
