package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.AgentPackage
import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.MarketAgentMode
import dev.amuse.forge.contract.PublishListingRequest
import dev.amuse.forge.contract.SkillPackage

/** Valid packages and requests; each test changes only what it is about. */
object MarketFixtures {

    fun skill(id: String = "water-coach") = SkillPackage(
        skillId = id,
        name = "Water coach",
        description = "Tracks glasses of water",
        whenToUse = "When the person mentions drinking or thirst",
        instructionsMarkdown = "# Water coach\nCount glasses, nudge every two hours.",
    )

    fun agent() = AgentPackage(
        name = "Hydration buddy",
        description = "Keeps me drinking water",
        iconKey = "water",
        systemPrompt = "You help the person drink 8 glasses a day.",
        toolNames = listOf("notifications", "memory_tool"),
        builtInSkillIds = listOf("fitness"),
        customSkills = listOf(skill()),
        defaultMode = MarketAgentMode.CHAT,
        allowedModes = listOf(MarketAgentMode.CHAT, MarketAgentMode.PLAN),
        model = "SPARK",
        goalMaxRounds = 3,
    )

    fun publishSkill(
        title: String = "Water coach",
        tags: List<String> = listOf("health"),
        skill: SkillPackage = skill(),
    ) = PublishListingRequest(
        kind = ListingKind.SKILL,
        title = title,
        summary = "A skill that counts your water",
        tags = tags,
        publisherName = "Uros",
        skill = skill,
    )

    fun publishAgent(title: String = "Hydration buddy", tags: List<String> = listOf("health", "habits")) =
        PublishListingRequest(
            kind = ListingKind.AGENT,
            title = title,
            summary = "An agent that keeps you hydrated",
            tags = tags,
            publisherName = "Uros",
            agent = agent(),
        )
}
