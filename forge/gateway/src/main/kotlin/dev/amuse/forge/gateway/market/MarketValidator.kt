package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.AgentPackage
import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.contract.MarketIssue
import dev.amuse.forge.contract.PublishListingRequest
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.contract.SkillPackage
import dev.amuse.forge.contract.UpdateListingRequest

// @trace skill-market-5d1e7a

/**
 * Every rule a market request must pass, collecting all issues at once (like the app's
 * `AgentValidator`). Package limits mirror the app's so anything the market accepts the app can
 * install. Tool names and built-in skill ids are format-checked only: the app owns those lists.
 */
object MarketValidator {
    const val MAX_TITLE = 60
    const val MAX_SUMMARY = 240
    const val MAX_PUBLISHER = 40
    const val MAX_CHANGELOG = 500
    const val MAX_TAGS = 5
    const val MAX_AGENT_NAME = 60
    const val MAX_DESCRIPTION = 240
    const val MAX_WHEN_TO_USE = 240
    const val MAX_SKILL_NAME = 60
    const val MAX_ICON_KEY = 40
    const val MAX_SYSTEM_PROMPT = 20_000
    const val MAX_SKILL_INSTRUCTIONS = 40_000
    const val MAX_CUSTOM_SKILLS = 10
    const val MAX_TOOLS = 32
    const val MIN_GOAL_ROUNDS = 1
    const val MAX_GOAL_ROUNDS = 10
    val KNOWN_MODELS = setOf("WONDER", "SPARK")

    private val SKILL_ID = Regex("[a-z0-9][a-z0-9_-]{2,63}")
    private val TAG = Regex("[a-z0-9-]{1,24}")
    private val TOOL_NAME = Regex("[a-z][a-z0-9_]{0,63}")

    fun validatePublish(request: PublishListingRequest): List<MarketIssue> =
        listingIssues(request.title, request.summary, request.tags) +
            text("publisherName", request.publisherName, MAX_PUBLISHER) +
            optionalText("changelog", request.changelog, MAX_CHANGELOG) +
            packageIssues(request.kind, request.skill, request.agent)

    fun validateVersion(kind: ListingKind, request: PublishVersionRequest): List<MarketIssue> =
        optionalText("changelog", request.changelog, MAX_CHANGELOG) + packageIssues(kind, request.skill, request.agent)

    fun validateUpdate(request: UpdateListingRequest): List<MarketIssue> = buildList {
        request.title?.let { addAll(text("title", it, MAX_TITLE)) }
        request.summary?.let { addAll(text("summary", it, MAX_SUMMARY)) }
        request.tags?.let { addAll(tagIssues(it)) }
        if (request.status == ListingStatus.HIDDEN) {
            add(MarketIssue("status", "status_not_allowed", "Only listed or unlisted can be set"))
        }
    }

    fun normalize(skill: SkillPackage): SkillPackage = skill.copy(
        skillId = skill.skillId.trim(),
        name = skill.name.trim(),
        description = skill.description.trim(),
        whenToUse = skill.whenToUse.trim(),
    )

    fun normalize(agent: AgentPackage): AgentPackage = agent.copy(
        name = agent.name.trim(),
        description = agent.description.trim(),
        iconKey = agent.iconKey.trim(),
        toolNames = agent.toolNames.map { it.trim() }.distinct().sorted(),
        builtInSkillIds = agent.builtInSkillIds.map { it.trim() }.distinct().sorted(),
        customSkills = agent.customSkills.map(::normalize).sortedBy { it.skillId },
        allowedModes = agent.allowedModes.distinct().sorted(),
    )

    fun normalizeTags(tags: List<String>): List<String> = tags.map { it.trim() }.distinct().sorted()

    private fun listingIssues(title: String, summary: String, tags: List<String>): List<MarketIssue> =
        text("title", title, MAX_TITLE) + text("summary", summary, MAX_SUMMARY) + tagIssues(tags)

    private fun tagIssues(tags: List<String>): List<MarketIssue> = buildList {
        if (tags.size > MAX_TAGS) add(MarketIssue("tags", "too_many_tags", "At most $MAX_TAGS tags"))
        tags.filterNot { TAG.matches(it.trim()) }.forEach {
            add(MarketIssue("tags", "bad_tag", "Tag '$it' must be lowercase letters, digits or '-', up to 24"))
        }
    }

    private fun packageIssues(kind: ListingKind, skill: SkillPackage?, agent: AgentPackage?): List<MarketIssue> {
        val matches = when (kind) {
            ListingKind.SKILL -> skill != null && agent == null
            ListingKind.AGENT -> agent != null && skill == null
        }
        if (!matches) {
            val field = if (kind == ListingKind.SKILL) "skill" else "agent"
            return listOf(MarketIssue(field, "package_kind_mismatch", "A $field listing carries exactly one $field package"))
        }
        return skill?.let { skillIssues("skill", it) } ?: agentIssues(agent!!)
    }

    private fun skillIssues(prefix: String, skill: SkillPackage): List<MarketIssue> = buildList {
        if (!SKILL_ID.matches(skill.skillId.trim())) {
            add(MarketIssue("$prefix.skillId", "bad_id", "Skill id must match ${SKILL_ID.pattern}"))
        }
        addAll(text("$prefix.name", skill.name, MAX_SKILL_NAME))
        addAll(text("$prefix.description", skill.description, MAX_DESCRIPTION))
        addAll(text("$prefix.whenToUse", skill.whenToUse, MAX_WHEN_TO_USE))
        addAll(text("$prefix.instructionsMarkdown", skill.instructionsMarkdown, MAX_SKILL_INSTRUCTIONS))
    }

    private fun agentIssues(agent: AgentPackage): List<MarketIssue> = buildList {
        addAll(text("agent.name", agent.name, MAX_AGENT_NAME))
        addAll(text("agent.description", agent.description, MAX_DESCRIPTION))
        addAll(text("agent.systemPrompt", agent.systemPrompt, MAX_SYSTEM_PROMPT))
        if (agent.iconKey.length > MAX_ICON_KEY) add(MarketIssue("agent.iconKey", "too_long", "Icon key is too long"))
        addAll(nameListIssues("agent.toolNames", agent.toolNames, TOOL_NAME, "bad_tool_name"))
        addAll(nameListIssues("agent.builtInSkillIds", agent.builtInSkillIds, SKILL_ID, "bad_id"))
        addAll(modeAndModelIssues(agent))
        addAll(customSkillIssues(agent.customSkills))
    }

    private fun nameListIssues(field: String, names: List<String>, rule: Regex, code: String): List<MarketIssue> = buildList {
        if (names.size > MAX_TOOLS) add(MarketIssue(field, "too_many", "At most $MAX_TOOLS entries"))
        names.filterNot { rule.matches(it.trim()) }.forEach { add(MarketIssue(field, code, "'$it' must match ${rule.pattern}")) }
    }

    private fun modeAndModelIssues(agent: AgentPackage): List<MarketIssue> = buildList {
        if (agent.defaultMode !in agent.allowedModes) {
            add(MarketIssue("agent.defaultMode", "default_mode_not_allowed", "The default mode must be one of the allowed modes"))
        }
        if (agent.model != null && agent.model !in KNOWN_MODELS) {
            add(MarketIssue("agent.model", "unknown_model", "Model must be one of $KNOWN_MODELS or empty"))
        }
        val rounds = agent.goalMaxRounds
        if (rounds != null && rounds !in MIN_GOAL_ROUNDS..MAX_GOAL_ROUNDS) {
            add(MarketIssue("agent.goalMaxRounds", "out_of_range", "Goal rounds must be $MIN_GOAL_ROUNDS..$MAX_GOAL_ROUNDS"))
        }
    }

    private fun customSkillIssues(skills: List<SkillPackage>): List<MarketIssue> = buildList {
        if (skills.size > MAX_CUSTOM_SKILLS) {
            add(MarketIssue("agent.customSkills", "too_many_skills", "At most $MAX_CUSTOM_SKILLS custom skills"))
            return@buildList
        }
        val ids = skills.map { it.skillId.trim() }
        ids.groupBy { it }.filterValues { it.size > 1 }.keys.forEach {
            add(MarketIssue("agent.customSkills", "duplicate_skill_id", "Two custom skills share the id '$it'"))
        }
        skills.forEachIndexed { i, skill -> addAll(skillIssues("agent.customSkills[$i]", skill)) }
    }

    private fun text(field: String, value: String, max: Int): List<MarketIssue> = when {
        value.isBlank() -> listOf(MarketIssue(field, "blank", "$field is required"))
        value.length > max -> listOf(MarketIssue(field, "too_long", "$field is longer than $max characters"))
        else -> emptyList()
    }

    private fun optionalText(field: String, value: String?, max: Int): List<MarketIssue> =
        if (value != null && value.length > max) listOf(MarketIssue(field, "too_long", "$field is longer than $max characters"))
        else emptyList()
}
