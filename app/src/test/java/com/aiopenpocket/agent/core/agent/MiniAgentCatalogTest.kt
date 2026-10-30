package com.aiopenpocket.agent.core.agent

import com.aiopenpocket.i18n.AppLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAgentCatalogTest {

  @Test
  fun `every roster agent can set heartbeats`() {
    for (agent in MiniAgentCatalog.all) {
      assertTrue("agent ${agent.id} lacks heartbeat tool", "heartbeat" in agent.toolNames)
      assertTrue("agent ${agent.id} prompt omits heartbeat", agent.systemPrompt.contains("heartbeat"))
    }
  }

  @Test
  fun `heartbeat supersedes work_manager in scoped agents`() {
    // heartbeat is built on top of work_manager; the agents' scope no longer needs the old tool.
    for (agent in MiniAgentCatalog.all) {
      assertTrue("agent ${agent.id} still scopes work_manager", "work_manager" !in agent.toolNames)
    }
  }

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private val cyrillic = Regex("\\p{IsCyrillic}")
  private val toolOrSkillRef = Regex("""load_skill\("[a-z_]+"\)|template="[a-z_]+"|mode=(?:push|agent)""")

  @Test
  fun `every preset has an English prompt with no Cyrillic`() {
    for (agent in MiniAgentCatalog.all) {
      assertTrue("agent ${agent.id} has no English prompt", agent.systemPromptEn != agent.systemPrompt)
      assertFalse("agent ${agent.id} English prompt has Cyrillic", cyrillic.containsMatchIn(agent.systemPromptEn))
    }
  }

  @Test
  fun `the English prompt names the same tools and skills as the Russian one`() {
    for (agent in MiniAgentCatalog.all) {
      for (tool in agent.toolNames) {
        assertEquals(
          "agent ${agent.id}: '$tool' mentioned in one language only",
          agent.systemPrompt.contains(tool), agent.systemPromptEn.contains(tool),
        )
      }
      val ru = toolOrSkillRef.findAll(agent.systemPrompt).map { it.value }.toSet()
      val en = toolOrSkillRef.findAll(agent.systemPromptEn).map { it.value }.toSet()
      assertEquals("agent ${agent.id}: skill/template/mode references differ", ru, en)
    }
  }

  @Test
  fun `the shown and sent prompt follows the interface language`() {
    val budget = MiniAgentCatalog.get("budget")!!.toDefinition()
    AppLanguage.code = AppLanguage.EN
    assertTrue(budget.localizedSystemPrompt.startsWith("You are Budget"))
    AppLanguage.code = AppLanguage.RU
    assertEquals(budget.systemPrompt, budget.localizedSystemPrompt)
  }

  @Test
  fun `a preset carrying a different prompt is shown as it is`() {
    val changed = MiniAgentCatalog.get("budget")!!.toDefinition().copy(systemPrompt = "a different prompt")
    AppLanguage.code = AppLanguage.EN
    assertEquals("a different prompt", changed.localizedSystemPrompt)
  }

  @Test
  fun `a user agent's prompt is shown as written`() {
    val own = MiniAgentCatalog.get("budget")!!.toDefinition().copy(id = "user-1", source = AgentSource.USER, systemPrompt = "my words")
    AppLanguage.code = AppLanguage.EN
    assertEquals("my words", own.localizedSystemPrompt)
  }
}
