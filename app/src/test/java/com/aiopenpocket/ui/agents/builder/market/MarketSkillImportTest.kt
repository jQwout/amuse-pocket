package com.aiopenpocket.ui.agents.builder.market

// @trace skill-from-the-market-in-the-agent-builder-acd66a

import com.aiopenpocket.agent.core.market.MarketSkillPackage
import org.junit.Assert.assertEquals
import org.junit.Test

/** The package-to-draft mapping: ids kept when free, suffixed on a collision, text carried over whole. */
class MarketSkillImportTest {

  private val pkg = MarketSkillPackage(
    skillId = "water-coach",
    name = " Water coach ",
    description = "Tracks glasses of water ",
    whenToUse = " When the person mentions drinking",
    instructionsMarkdown = "# Water coach\n\nCount glasses.  ",
  )

  @Test
  fun `a free id is kept as is and the texts are carried over`() {
    val draft = MarketSkillImport.toDraft(pkg, takenIds = setOf("other-skill")) { "ffffffff" }

    assertEquals("water-coach", draft.id)
    assertEquals("Water coach", draft.name)
    assertEquals("Tracks glasses of water", draft.description)
    assertEquals("When the person mentions drinking", draft.whenToUse)
    // The instruction is the stranger's text: it is not trimmed or otherwise rewritten.
    assertEquals("# Water coach\n\nCount glasses.  ", draft.instructions)
  }

  @Test
  fun `an id this agent already uses gets a suffix`() {
    val draft = MarketSkillImport.toDraft(pkg, takenIds = setOf("water-coach")) { "1a2b3c4d" }

    assertEquals("water-coach-1a2b3c4d", draft.id)
  }

  @Test
  fun `an id of a built-in skill gets a suffix too`() {
    // "budget" is a built-in: a custom skill under that id would lose every registry lookup to it.
    val draft = MarketSkillImport.toDraft(pkg.copy(skillId = "budget"), takenIds = emptySet()) { "deadbeef" }

    assertEquals("budget-deadbeef", draft.id)
  }

  @Test
  fun `the random suffix is eight characters of hex`() {
    val draft = MarketSkillImport.toDraft(pkg, takenIds = setOf("water-coach"))

    val suffix = draft.id.removePrefix("water-coach-")
    assertEquals(8, suffix.length)
    assert(suffix.all { it in '0'..'9' || it in 'a'..'f' }) { "not hex: $suffix" }
  }
}
