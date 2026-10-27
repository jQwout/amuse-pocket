package com.aiopenpocket.agent.core.mcp

import com.aiopenpocket.agent.core.agent.AgentValidator
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared name dictionary: every tool and built-in skill has its own Russian name, and an unknown name
 * turns into a readable label — without a crash and without an empty string.
 */
class CapabilityNamesTest {

  @Test
  fun `every known tool has its own name`() {
    // A new tool without a name fails here — otherwise it would show up in the chips and the bar under its raw id.
    AgentValidator.KNOWN_TOOL_NAMES.forEach { name ->
      assertTrue("$name: нет имени", CapabilityNames.isKnownTool(name))
      assertFalse("$name: имя совпадает с id", CapabilityNames.tool(name).contains('_'))
    }
  }

  @Test
  fun `every built-in skill has its own title`() {
    AgentValidator.KNOWN_SKILL_IDS.forEach { id ->
      assertTrue("$id: нет названия", CapabilityNames.skill(id).first().isUpperCase())
      assertFalse("$id: название совпадает с id", CapabilityNames.skill(id) == id)
    }
  }

  @Test
  fun `call names the action when it is known`() {
    assertEquals(
      "Календарь: удалить событие",
      CapabilityNames.call("calendar", mapOf("action" to JsonPrimitive("delete_event"))),
    )
    assertEquals(
      "Навыки: загрузить «Бюджет»",
      CapabilityNames.call("load_skill", mapOf("id" to JsonPrimitive("budget"))),
    )
  }

  @Test
  fun `call falls back to the tool name for an unknown or missing action`() {
    assertEquals("Календарь", CapabilityNames.call("calendar", mapOf("action" to JsonPrimitive("teleport"))))
    assertEquals("Карточки", CapabilityNames.call("render_card", emptyMap()))
  }

  @Test
  fun `a bare action name used as a function name is still human`() {
    assertEquals("Память: найти факты", CapabilityNames.tool("search_memory"))
  }

  @Test
  fun `unknown names stay readable and never blank`() {
    assertEquals("Weather api", CapabilityNames.tool("weather_api"))
    assertEquals("Инструмент", CapabilityNames.tool(""))
    assertEquals("Инструмент", CapabilityNames.tool(" _ "))
    assertEquals("Running plan", CapabilityNames.skill("running-plan"))
    assertEquals("Навык", CapabilityNames.skill(""))
  }
}
