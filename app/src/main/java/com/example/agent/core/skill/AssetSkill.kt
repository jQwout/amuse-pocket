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

  // Содержимое ассета неизменно на всё время жизни процесса — кэшируем, чтобы повторные
  // load_skill(id) в одном диалоге не переоткрывали и не перечитывали файл заново.
  @Volatile
  private var cachedInstructions: String? = null

  override suspend fun instructions(): String {
    cachedInstructions?.let { return it }
    return withContext(Dispatchers.IO) {
      val text = try {
        application.assets.open("skills/${manifest.id}/SKILL.md")
          .bufferedReader()
          .use { it.readText() }
      } catch (_: Exception) {
        "Инструкция для навыка '${manifest.id}' не найдена."
      }
      cachedInstructions = text
      text
    }
  }
}
