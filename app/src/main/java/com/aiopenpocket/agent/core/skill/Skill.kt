package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc

/**
 * A skill manifest — cheap metadata for the catalog (level 1 of progressive disclosure).
 * Declared in code (like PersonaInfo) so the catalog is built without reading assets.
 */
data class SkillManifest(
  val id: String,
  val description: String,
  val whenToUse: String,
)

/**
 * A skill = a packaged procedure/knowledge the model loads on demand.
 *
 * Level 1: the [manifest] is visible in the system prompt (the catalog, cheap).
 * Level 2: the model calls load_skill(id) → the [instructions] go into the context.
 * Level 3: inside the instruction — references to resources the model reads with ordinary tools.
 */
interface Skill {
  val manifest: SkillManifest

  /** The full instruction text (SKILL.md). Loaded lazily, only on activation. */
  suspend fun instructions(): String
}
