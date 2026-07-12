package com.aiopenpocket.agent.core.agent

import com.aiopenpocket.agent.loop.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure rules — no Robolectric needed: [AgentValidator.validate] never touches Android. */
class AgentValidatorTest {

  private val validator = AgentValidator

  private fun validDefinition(id: String = "my-test-agent") = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Тестовый агент",
    description = "Агент для проверки правил валидатора.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент для unit-тестов.",
    toolNames = setOf("memory_tool", "render_card"),
    skillIds = setOf("budget"),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT, AgentMode.PLAN, AgentMode.GOAL, AgentMode.REFLECT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = true),
    allowedWidgetTemplates = setOf("reminder", "table"),
    hooks = emptyList(),
    configVersion = 1,
    status = AgentStatus.DRAFT,
  )

  @Test
  fun `a well-formed definition has no issues`() {
    assertTrue(validator.validate(validDefinition()).isEmpty())
  }

  @Test
  fun `every preset in the catalog adapts to a fully valid definition`() {
    for (agent in MiniAgentCatalog.all) {
      val issues = validator.validate(agent.toDefinition())
      assertTrue("preset '${agent.id}' failed validation: $issues", issues.isEmpty())
    }
  }

  @Test
  fun `id colliding with a preset is rejected, but only for USER-sourced definitions`() {
    assertTrue(validator.validate(validDefinition().copy(id = "budget")).any { it.code == "preset_id_collision" })
    // The preset's own adapted definition legitimately reuses its own id — not a collision with itself.
    assertTrue(validator.validate(MiniAgentCatalog.get("budget")!!.toDefinition()).none { it.code == "preset_id_collision" })
  }

  @Test
  fun `defaultMode must be a member of allowedModes`() {
    assertTrue(
      validator.validate(validDefinition().copy(defaultMode = AgentMode.GOAL, allowedModes = setOf(AgentMode.CHAT)))
        .any { it.code == "default_mode_not_allowed" },
    )
  }

  @Test
  fun `memory namespace must be exactly agent-colon-id-colon, rejecting path-traversal-shaped values`() {
    assertTrue(
      validator.validate(validDefinition().copy(memoryPolicy = AgentMemoryPolicy("../x", false)))
        .any { it.code == "invalid_memory_namespace" },
    )
  }

  @Test
  fun `id must match the lowercase-alnum-hyphen regex, 3 to 64 chars total`() {
    assertTrue(validator.validate(validDefinition(id = "AB")).any { it.code == "invalid_id_format" }) // uppercase + too short
    assertTrue(validator.validate(validDefinition(id = "ab")).any { it.code == "invalid_id_format" }) // 2 chars, below the 3 min
    assertTrue(validator.validate(validDefinition(id = "-abc")).any { it.code == "invalid_id_format" }) // must start alnum
    assertTrue(validator.validate(validDefinition(id = "a".repeat(65))).any { it.code == "invalid_id_format" }) // 65 chars, above the 64 max
    assertTrue(validator.validate(validDefinition(id = "a".repeat(64))).none { it.code == "invalid_id_format" }) // 64 chars, exactly at the max
  }

  @Test
  fun `blank required fields are rejected`() {
    assertTrue(validator.validate(validDefinition().copy(name = "   ")).any { it.code == "blank_name" })
    assertTrue(validator.validate(validDefinition().copy(description = "")).any { it.code == "blank_description" })
    assertTrue(validator.validate(validDefinition().copy(iconKey = "")).any { it.code == "blank_icon_key" })
    assertTrue(validator.validate(validDefinition().copy(systemPrompt = "")).any { it.code == "blank_system_prompt" })
  }

  @Test
  fun `name, description and system prompt enforce their v1 length limits`() {
    assertTrue(
      validator.validate(validDefinition().copy(name = "x".repeat(AgentValidator.MAX_AGENT_NAME_CHARS + 1)))
        .any { it.code == "name_too_long" },
    )
    assertTrue(
      validator.validate(validDefinition().copy(description = "x".repeat(AgentValidator.MAX_DESCRIPTION_CHARS + 1)))
        .any { it.code == "description_too_long" },
    )
    assertTrue(
      validator.validate(validDefinition().copy(systemPrompt = "x".repeat(AgentValidator.MAX_SYSTEM_PROMPT_CHARS + 1)))
        .any { it.code == "system_prompt_too_long" },
    )
  }

  @Test
  fun `unknown tools, skills and widget templates are each rejected by name`() {
    assertTrue(validator.validate(validDefinition().copy(toolNames = setOf("not_a_real_tool"))).any { it.code == "unknown_tool" })
    assertTrue(validator.validate(validDefinition().copy(skillIds = setOf("not_a_real_skill"))).any { it.code == "unknown_skill" })
    assertTrue(
      validator.validate(validDefinition().copy(allowedWidgetTemplates = setOf("not_a_real_widget")))
        .any { it.code == "unknown_widget_template" },
    )
  }

  @Test
  fun `every known tool, skill and widget template together produce no unknown-code issues`() {
    val issues = validator.validate(
      validDefinition().copy(
        toolNames = AgentValidator.KNOWN_TOOL_NAMES,
        skillIds = AgentValidator.KNOWN_SKILL_IDS,
        allowedWidgetTemplates = AgentValidator.KNOWN_WIDGET_TEMPLATES,
      ),
    )
    assertTrue(issues.none { it.code.startsWith("unknown_") })
  }

  @Test
  fun `MAX_CUSTOM_SKILL_CHARS is defined for the upcoming custom-skill-content limit`() {
    // Not yet enforced by validate() — AgentDefinition has no inline skill-content field until the
    // custom skill entity lands (later task). Locks the v1 constant value the brief specifies.
    assertEquals(40_000, AgentValidator.MAX_CUSTOM_SKILL_CHARS)
  }
}
