package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A skill whose body lives in the asset `assets/skills/{id}/SKILL.md`.
 * The manifest is defined in code by a subclass (cheap for the catalog), the body is read lazily.
 */
abstract class AssetSkill(
  private val application: Application,
) : Skill {

  // The asset content does not change for the life of the process — cache it so repeated
  // load_skill(id) calls in one conversation do not reopen and reread the file.
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
