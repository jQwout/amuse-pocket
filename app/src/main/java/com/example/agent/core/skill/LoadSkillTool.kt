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
 * Меташар: загружает подробную инструкцию навыка по id (уровень 2 progressive disclosure).
 * Для агент-лупа это обычный Tool — результат уходит обратно в контекст как tool-message.
 * Грузить можно только зарегистрированные навыки (whitelist) — built-in ИЛИ agent-owned Room custom
 * (см. composite [SkillRegistry]), произвольный текст/путь — нельзя. Этот Tool сам по себе не знает,
 * КАКОМУ агенту разрешён конкретный id — тот скоуп проверяет вызывающий цикл
 * ([com.aiopenpocket.agent.loop.ReactLoopRunner]'s isSkillAllowed, сверяет id с
 * [com.aiopenpocket.agent.core.agent.AgentDefinition.skillIds]) ДО того, как этот Tool вообще исполнится.
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
