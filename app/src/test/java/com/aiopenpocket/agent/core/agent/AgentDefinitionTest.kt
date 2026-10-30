package com.aiopenpocket.agent.core.agent

import android.app.Application
import com.aiopenpocket.agent.core.skill.skills.BudgetSkill
import com.aiopenpocket.agent.core.skill.skills.DiarySkill
import com.aiopenpocket.agent.core.skill.skills.FitnessSkill
import com.aiopenpocket.agent.core.skill.skills.LanguagesSkill
import com.aiopenpocket.agent.loop.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [MiniAgent.toDefinition] — the preset adapter side of the immutable [AgentDefinition] contract.
 * Robolectric is only needed for the real-catalog drift test (it constructs the actual [Skill]
 * beans, which take an [Application]); the rest are plain field-mapping assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentDefinitionTest {

  @Test
  fun `preset adapter sources from PRESET`() {
    assertEquals(AgentSource.PRESET, MiniAgentCatalog.get("budget")!!.toDefinition().source)
  }

  @Test
  fun `toDefinition copies identity and scope fields verbatim from the MiniAgent`() {
    val agent = MiniAgentCatalog.get("diary")!!
    val definition = agent.toDefinition()

    assertEquals(agent.id, definition.id)
    assertEquals(agent.name, definition.name)
    assertEquals(agent.description, definition.description)
    assertEquals(agent.iconKey, definition.iconKey)
    assertEquals(agent.systemPrompt, definition.systemPrompt)
    assertEquals(agent.toolNames, definition.toolNames)
  }

  @Test
  fun `toDefinition marks presets as already-published version 1 with no hooks`() {
    val definition = MiniAgentCatalog.get("tasks")!!.toDefinition()

    assertEquals(AgentStatus.PUBLISHED, definition.status)
    assertEquals(1L, definition.configVersion)
    assertTrue(definition.hooks.isEmpty())
    assertTrue("PUBLISHED presets must report enabled=true", definition.enabled)
  }

  /**
   * Presets offer ONLY Chat and Reflection. Planning (Plan/Goal) is a privilege of agents the user built
   * themselves and explicitly enabled the modes for: the full set used to be handed to every preset in one
   * shared line, and Reminders and Diary offered goal mode for no reason at all.
   */
  @Test
  fun `toDefinition gives presets chat and reflect only, never planning modes`() {
    val definition = MiniAgentCatalog.get("reminders")!!.toDefinition()

    assertEquals(AgentMode.CHAT, definition.defaultMode)
    assertEquals(setOf(AgentMode.CHAT, AgentMode.REFLECT), definition.allowedModes)
    assertTrue("планирование пресетам не выдаём", AgentMode.PLAN !in definition.allowedModes)
    assertTrue("планирование пресетам не выдаём", AgentMode.GOAL !in definition.allowedModes)
    assertTrue("TUTOR is lesson-internal, never user-selectable for a mini-agent chat", AgentMode.TUTOR !in definition.allowedModes)
  }

  @Test
  fun `toDefinition scopes memory to an agent-prefixed namespace and keeps shared profile visible`() {
    val definition = MiniAgentCatalog.get("tasks")!!.toDefinition()

    assertEquals(AgentMemoryPolicy(namespace = "agent:tasks:", includeSharedProfile = true), definition.memoryPolicy)
  }

  @Test
  fun `toDefinition allows every known widget template, matching today's unrestricted render_card usage`() {
    val definition = MiniAgentCatalog.get("budget")!!.toDefinition()

    assertEquals(AgentValidator.KNOWN_WIDGET_TEMPLATES, definition.allowedWidgetTemplates)
  }

  @Test
  fun `preset skillIds mapping is exactly budget, languages and diary`() {
    val mapped = MiniAgentCatalog.all.associate { it.id to it.toDefinition().skillIds }

    assertEquals(setOf("budget"), mapped.getValue("budget"))
    assertEquals(setOf("languages"), mapped.getValue("languages"))
    assertEquals(setOf("diary"), mapped.getValue("diary"))
    assertEquals(emptySet<String>(), mapped.getValue("tasks"))
    assertEquals(emptySet<String>(), mapped.getValue("reminders"))
  }

  @Test
  fun `preset skillIds mapping matches the real skill and agent catalogs, so drift fails loudly`() {
    // Pins against the ACTUAL registries instead of two hand-written literals that only agree with
    // each other by coincidence: per the plan's 2026-07-12 note, if a skill or agent id is renamed
    // or a new one is added, this must fail instead of silently drifting from MiniAgentCatalog /
    // SkillModule.
    val application: Application = RuntimeEnvironment.getApplication()
    val realSkillIds = setOf(
      BudgetSkill(application).manifest.id,
      DiarySkill(application).manifest.id,
      FitnessSkill(application).manifest.id,
      LanguagesSkill(application).manifest.id,
    )
    val realAgentIds = MiniAgentCatalog.all.map { it.id }.toSet()
    val expectedSkillAgents = realSkillIds.intersect(realAgentIds)

    assertEquals(setOf("budget", "languages", "diary"), expectedSkillAgents)
    for (agent in MiniAgentCatalog.all) {
      val expected = if (agent.id in expectedSkillAgents) setOf(agent.id) else emptySet()
      assertEquals("skillIds mismatch for agent ${agent.id}", expected, agent.toDefinition().skillIds)
    }
  }

  @Test
  fun `toDefinition carries stateKeyPrefixes verbatim for agents that declare them, empty otherwise`() {
    // The languages preset's goal/lang/grammar prefixes drive PrePromptAssembler's deterministic
    // "agent state" memory section (see MiniAgent.stateKeyPrefixes) - this must survive the jump to
    // AgentDefinition, or AgentResolver callers would silently lose that feature for this agent.
    assertEquals(
      setOf("lang:", "goal:languages", "grammar:"),
      MiniAgentCatalog.get("languages")!!.toDefinition().stateKeyPrefixes,
    )
    assertEquals(emptySet<String>(), MiniAgentCatalog.get("budget")!!.toDefinition().stateKeyPrefixes)
  }

  @Test
  fun `enabled mirrors PUBLISHED status only`() {
    val base = MiniAgentCatalog.get("budget")!!.toDefinition()

    assertTrue(base.copy(status = AgentStatus.PUBLISHED).enabled)
    assertTrue(!base.copy(status = AgentStatus.DRAFT).enabled)
    assertTrue(!base.copy(status = AgentStatus.ARCHIVED).enabled)
  }
}
