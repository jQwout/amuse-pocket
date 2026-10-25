package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnContextHolder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Security regression for the per-agent widget allowlist (Task 7 of the user-agent-builder plan):
 * `render_card`'s `template` argument must be checked against the bound agent's
 * [AgentDefinition.allowedWidgetTemplates] IN CODE, before any card JSON is built — the tool
 * schema's `enum` only guides a well-behaved model, it is not a security boundary, so
 * [RenderCardTool.execute] itself must reject a disallowed/unknown template before formatting a
 * card. Plain chat (no bound agent) must keep the exact unrestricted behavior it always had — the
 * one path this task must NOT touch, the same rule [MemoryTools]/[FileTools] follow for their own
 * per-agent scoping (namespace / sandbox root).
 */
class RenderCardPolicyTest {

  private lateinit var holder: TurnContextHolder
  private lateinit var tool: RenderCardTool

  @Before
  fun setUp() {
    holder = TurnContextHolder()
    tool = RenderCardTool(holder)
  }

  private fun agent(id: String, allowedWidgetTemplates: Set<String>) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Тестовый агент $id",
    description = "Кастомный агент для проверки allowlist карточек.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент для unit-тестов.",
    toolNames = setOf("render_card"),
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = allowedWidgetTemplates,
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  private fun args(template: String): Map<String, JsonElement> =
    mapOf("template" to JsonPrimitive(template))

  @Test
  fun `bound agent renders a template inside its own allowlist`() = runTest {
    holder.set("conv1", agent("builder-bot", setOf("todo_list")))

    val result = tool.execute(args("todo_list"))

    assertEquals(ToolResult.Success("""{"template":"todo_list","params":null}"""), result)
  }

  @Test
  fun `bound agent is rejected for a known template outside its own allowlist, no card is built`() = runTest {
    // lesson_quiz is a real, known template (LESSON_CARD_TEMPLATES) - just not one this agent was
    // granted, e.g. a "general" builder agent scoped to GENERAL_CARD_TEMPLATES only.
    holder.set("conv1", agent("builder-bot", GENERAL_CARD_TEMPLATES))

    val result = tool.execute(args("lesson_quiz"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse("denied widget template must be a non-recoverable error", (result as ToolResult.Error).recoverable)
  }

  @Test
  fun `bound agent is rejected for a template unknown to the tool entirely`() = runTest {
    holder.set("conv1", agent("builder-bot", GENERAL_CARD_TEMPLATES + LESSON_CARD_TEMPLATES))

    val result = tool.execute(args("not_a_real_template"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse("unknown widget template must be a non-recoverable error", (result as ToolResult.Error).recoverable)
  }

  @Test
  fun `plain chat keeps rendering any known template unrestricted, exactly like before`() = runTest {
    holder.set("conv1", null)

    val result = tool.execute(args("lesson_quiz"))

    assertEquals(ToolResult.Success("""{"template":"lesson_quiz","params":null}"""), result)
  }

  @Test
  fun `general and lesson template constants are disjoint and their union is what the tool recognizes`() {
    assertTrue(GENERAL_CARD_TEMPLATES.intersect(LESSON_CARD_TEMPLATES).isEmpty())
    assertTrue("todo_list" in GENERAL_CARD_TEMPLATES)
    assertTrue("lesson_quiz" in LESSON_CARD_TEMPLATES)
  }

  @Test
  fun `general, lesson and retired templates together match AgentValidator's known widget templates exactly`() {
    // Two hand-maintained copies of "every template that exists" now live in the codebase
    // (AgentValidator.KNOWN_WIDGET_TEMPLATES and this split) - pin them together so a future new
    // template added to only one of them fails loudly instead of silently drifting. Retired templates
    // stay "known" to the validator so an already-saved agent listing them can still be published.
    assertEquals(
      AgentValidator.KNOWN_WIDGET_TEMPLATES,
      GENERAL_CARD_TEMPLATES + LESSON_CARD_TEMPLATES + RETIRED_CARD_TEMPLATES,
    )
  }

  /** switch (пустой обработчик) и weather_card (нет источника погоды) модели больше не предлагаются. */
  @Test
  fun `retired templates are not offered and a bound agent cannot render them`() = runTest {
    assertTrue(RETIRED_CARD_TEMPLATES.intersect(GENERAL_CARD_TEMPLATES + LESSON_CARD_TEMPLATES).isEmpty())
    assertFalse(tool.description.contains("switch"))
    assertFalse(tool.description.contains("weather_card"))

    holder.set("conv1", agent("builder-bot", GENERAL_CARD_TEMPLATES + RETIRED_CARD_TEMPLATES))
    for (template in RETIRED_CARD_TEMPLATES) {
      assertTrue("$template must be refused", tool.execute(args(template)) is ToolResult.Error)
    }
  }
}
