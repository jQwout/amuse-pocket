package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.contract.MarketAgentMode
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.contract.UpdateListingRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketValidatorTest {

    private fun codes(issues: List<dev.amuse.forge.contract.MarketIssue>) = issues.map { it.code }.toSet()

    @Test
    fun `valid skill and agent requests pass`() {
        assertEquals(emptyList(), MarketValidator.validatePublish(MarketFixtures.publishSkill()))
        assertEquals(emptyList(), MarketValidator.validatePublish(MarketFixtures.publishAgent()))
    }

    @Test
    fun `the body must match the kind, exactly one of them`() {
        val agentKindWithSkill = MarketFixtures.publishSkill().copy(kind = ListingKind.AGENT)
        assertTrue("package_kind_mismatch" in codes(MarketValidator.validatePublish(agentKindWithSkill)))

        val both = MarketFixtures.publishSkill().copy(agent = MarketFixtures.agent())
        assertTrue("package_kind_mismatch" in codes(MarketValidator.validatePublish(both)))

        val none = MarketFixtures.publishSkill().copy(skill = null)
        assertTrue("package_kind_mismatch" in codes(MarketValidator.validatePublish(none)))
    }

    @Test
    fun `listing fields are bounded and every issue is reported at once`() {
        val bad = MarketFixtures.publishSkill(
            title = " ",
            tags = listOf("ok", "Has Space", "a", "b", "c", "d"),
        ).copy(summary = "x".repeat(241), publisherName = "")
        val found = codes(MarketValidator.validatePublish(bad))
        assertTrue("blank" in found)
        assertTrue("too_long" in found)
        assertTrue("bad_tag" in found)
        assertTrue("too_many_tags" in found)
        assertTrue(MarketValidator.validatePublish(bad).size >= 4)
    }

    @Test
    fun `skill limits mirror the app`() {
        val tooLong = MarketFixtures.skill().copy(instructionsMarkdown = "x".repeat(40_001))
        assertTrue("too_long" in codes(MarketValidator.validatePublish(MarketFixtures.publishSkill(skill = tooLong))))

        val blank = MarketFixtures.skill().copy(instructionsMarkdown = "  ")
        assertTrue("blank" in codes(MarketValidator.validatePublish(MarketFixtures.publishSkill(skill = blank))))

        val badId = MarketFixtures.skill(id = "Bad Id!")
        assertTrue("bad_id" in codes(MarketValidator.validatePublish(MarketFixtures.publishSkill(skill = badId))))
    }

    @Test
    fun `agent rules - modes, model, rounds, skills, tools`() {
        val agent = MarketFixtures.agent().copy(
            defaultMode = MarketAgentMode.GOAL,
            allowedModes = listOf(MarketAgentMode.CHAT),
            model = "GPT-9",
            goalMaxRounds = 11,
            toolNames = listOf("reminder", "rm -rf"),
            customSkills = List(11) { MarketFixtures.skill(id = "skill-$it") },
            systemPrompt = "x".repeat(20_001),
        )
        val found = codes(MarketValidator.validatePublish(MarketFixtures.publishAgent().copy(agent = agent)))
        assertEquals(
            setOf("default_mode_not_allowed", "unknown_model", "out_of_range", "bad_tool_name", "too_many_skills", "too_long"),
            found,
        )
    }

    @Test
    fun `two custom skills of one agent may not share an id`() {
        val agent = MarketFixtures.agent().copy(customSkills = listOf(MarketFixtures.skill("dup"), MarketFixtures.skill("dup")))
        assertTrue("duplicate_skill_id" in codes(MarketValidator.validatePublish(MarketFixtures.publishAgent().copy(agent = agent))))
    }

    @Test
    fun `a new version must keep the listing's kind`() {
        val asAgent = PublishVersionRequest(skill = MarketFixtures.skill())
        assertTrue("package_kind_mismatch" in codes(MarketValidator.validateVersion(ListingKind.AGENT, asAgent)))
        assertEquals(emptyList(), MarketValidator.validateVersion(ListingKind.SKILL, asAgent))
    }

    @Test
    fun `the publisher cannot hide or unhide a listing`() {
        assertTrue("status_not_allowed" in codes(MarketValidator.validateUpdate(UpdateListingRequest(status = ListingStatus.HIDDEN))))
        assertEquals(emptyList(), MarketValidator.validateUpdate(UpdateListingRequest(status = ListingStatus.UNLISTED)))
    }

    @Test
    fun `normalizing sorts, dedupes and trims so the hash is stable`() {
        val messy = MarketFixtures.agent().copy(
            name = "  Hydration buddy ",
            toolNames = listOf("memory_tool", "notifications", "memory_tool"),
            allowedModes = listOf(MarketAgentMode.PLAN, MarketAgentMode.CHAT),
        )
        val clean = MarketValidator.normalize(messy)
        assertEquals("Hydration buddy", clean.name)
        assertEquals(listOf("memory_tool", "notifications"), clean.toolNames)
        assertEquals(listOf(MarketAgentMode.CHAT, MarketAgentMode.PLAN), clean.allowedModes)
        assertEquals(MarketValidator.normalize(MarketFixtures.agent()), MarketValidator.normalize(clean))
    }
}
