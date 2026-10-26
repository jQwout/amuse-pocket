package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc
// @trace user-agent-builder-848153

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * A composite skill registry (a mirror of [com.aiopenpocket.agent.core.agent.AgentResolver]: a
 * read-only preset store + a Room custom store). Source 1 — built-in [Skill] beans from the DI
 * multibinding Set<Skill> (read-only, as before). Source 2 — [userSkillRepository], agent-owned custom
 * skills from Room. All methods suspend — the Room part needs IO.
 *
 * On an id collision the built-in WINS: an id already taken by a built-in skill never resolves to a
 * custom row. In practice collisions do not happen — [validateUserSkill] forbids publishing a custom
 * skill with a built-in id — but resolution stays deterministic (built-in wins) rather than relying
 * only on publish having been called before the built-in set could change.
 */
@SingleIn(AppScope::class)
@Inject
class SkillRegistry(
  builtIn: Set<@JvmSuppressWildcards Skill>,
  private val userSkillRepository: UserSkillRepository,
) {
  private val builtInMap = builtIn.associateBy { it.manifest.id }

  suspend fun get(id: String): Skill? = builtInMap[id] ?: userSkillRepository.get(id)

  suspend fun ids(): Set<String> = builtInMap.keys + userSkillRepository.ids()

  /**
   * Built-in ids only. The builder's "Skills" picker needs exactly this: [ids] is app-wide, so
   * offering it there let one agent tick another agent's custom skill — which `publish` then refuses
   * ("the skill already belongs to another agent"), after the wizard had already accepted it. An
   * agent's OWN custom skills are shown from its draft, not from this registry.
   */
  fun builtInIds(): Set<String> = builtInMap.keys

  /**
   * The cheap catalog for the system prompt (level 1) — metadata only, no asset reads. Empty if there
   * are no skills (then the section is not added to the prompt). A non-null [allowedIds] narrows the
   * catalog to a particular agent's skills (built-in and/or its own custom ones), see
   * [com.aiopenpocket.agent.core.prompt.PrePromptAssembler] and [LoadSkillTool].
   */
  suspend fun catalog(allowedIds: Set<String>? = null): String {
    // Custom skills whose id collides with a built-in are excluded here too, so the catalog text
    // never advertises an entry that get() would then resolve to the built-in instead.
    val custom = userSkillRepository.getAll().filter { it.manifest.id !in builtInMap.keys }
    return (builtInMap.values + custom)
      .filter { allowedIds == null || it.manifest.id in allowedIds }
      .joinToString("\n") { s ->
        "- ${s.manifest.id}: ${s.manifest.description} (когда: ${s.manifest.whenToUse})"
      }
  }
}
