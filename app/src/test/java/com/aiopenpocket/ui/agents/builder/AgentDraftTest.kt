package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.tools.GENERAL_CARD_TEMPLATES
import com.aiopenpocket.agent.tools.LESSON_CARD_TEMPLATES
import com.aiopenpocket.agent.tools.RETIRED_CARD_TEMPLATES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDraftTest {

  /**
   * The card-template chips are folded away on the Capabilities step, so a fresh draft has to start with
   * the general cards itself: one that began with none would publish an agent unable to draw a single card.
   * The lesson and retired templates stay out — those are the chips the fold hides for good reason.
   */
  @Test
  fun `a new draft allows the general cards and nothing else`() {
    val draft = AgentDraft.new(id = "agent-fresh")

    assertEquals(GENERAL_CARD_TEMPLATES, draft.allowedWidgetTemplates)
    assertTrue(draft.allowedWidgetTemplates.none { it in LESSON_CARD_TEMPLATES })
    assertTrue(draft.allowedWidgetTemplates.none { it in RETIRED_CARD_TEMPLATES })
    assertEquals(GENERAL_CARD_TEMPLATES, draft.toDefinition().allowedWidgetTemplates)
  }
}
