package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * A meta tool: loads a skill's detailed instruction by id (level 2 of progressive disclosure).
 * For the agent loop it is an ordinary Tool — the result goes back into the context as a tool message.
 * Only registered skills can be loaded (a whitelist) — built-in OR agent-owned Room custom ones
 * (see the composite [SkillRegistry]); arbitrary text/paths cannot. This Tool by itself does not know
 * WHICH agent a particular id is allowed for — the calling loop checks that scope
 * ([com.aiopenpocket.agent.loop.ReactLoopRunner]'s isSkillAllowed, matching the id against
 * [com.aiopenpocket.agent.core.agent.AgentDefinition.skillIds]) BEFORE this Tool runs at all.
 */
@Inject
@SingleIn(AppScope::class)
class LoadSkillTool(
  private val registry: SkillRegistry,
) : Tool {

  override val name = "load_skill"
  override val description =
    "Загрузить подробную инструкцию навыка по его id, когда задача подходит под навык из каталога доступных навыков."

  override val schema = toolSchema(name, description) {
    string("id", "ID навыка из каталога доступных навыков", required = true)
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val id = arguments.string("id") ?: return missingArg("id")
    val skill = registry.get(id)
      ?: return ToolResult.Error(
        "Unknown skill: $id. Доступные: ${registry.ids().joinToString()}",
      )
    return ToolResult.Success(skill.instructions())
  }
}
