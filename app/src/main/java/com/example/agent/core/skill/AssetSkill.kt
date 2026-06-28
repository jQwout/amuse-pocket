package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Навык, тело которого лежит в ассете `assets/skills/{id}/SKILL.md`.
 * Манифест задаётся в коде наследником (дёшево для каталога), тело читается лениво.
 */
abstract class AssetSkill(
  private val application: Application,
) : Skill {

  override suspend fun instructions(): String = withContext(Dispatchers.IO) {
    try {
      application.assets.open("skills/${manifest.id}/SKILL.md")
        .bufferedReader()
        .use { it.readText() }
    } catch (_: Exception) {
      "Инструкция для навыка '${manifest.id}' не найдена."
    }
  }
}
