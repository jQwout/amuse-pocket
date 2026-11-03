package com.aiopenpocket.ui.agents.builder.market

// @trace skill-from-the-market-in-the-agent-builder-acd66a

import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.market.MarketSkillPackage
import com.aiopenpocket.ui.agents.builder.CustomSkillDraft
import java.util.UUID

/**
 * A market package becomes one of the agent's own skills: a [CustomSkillDraft] in the builder's draft,
 * saved later as a `user_skills` row exactly like a skill typed in by hand. The package suggests an id;
 * it is kept when free and gets a random suffix when another skill of this agent, a built-in or a skill
 * ticked in the draft already has it. A built-in's id would otherwise win the `SkillRegistry` lookup
 * and leave the imported skill silently dead.
 */
object MarketSkillImport {

  fun toDraft(
    pkg: MarketSkillPackage,
    takenIds: Set<String>,
    suffix: () -> String = ::randomSuffix,
  ): CustomSkillDraft {
    val wanted = pkg.skillId.trim()
    val id = if (wanted in takenIds || wanted in AgentValidator.KNOWN_SKILL_IDS) "$wanted-${suffix()}" else wanted
    return CustomSkillDraft(
      id = id,
      name = pkg.name.trim(),
      description = pkg.description.trim(),
      whenToUse = pkg.whenToUse.trim(),
      instructions = pkg.instructionsMarkdown,
    )
  }

  private fun randomSuffix(): String = UUID.randomUUID().toString().replace("-", "").take(SUFFIX_LENGTH)

  private const val SUFFIX_LENGTH = 8
}
