package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc

/**
 * Манифест навыка — дешёвые метаданные для каталога (уровень 1 progressive disclosure).
 * Объявляется в коде (как PersonaInfo), чтобы каталог собирался без чтения ассетов.
 */
data class SkillManifest(
  val id: String,
  val description: String,
  val whenToUse: String,
)

/**
 * Навык = упакованная процедура/знание, которое модель подгружает по требованию.
 *
 * Уровень 1: [manifest] виден в системном промпте (каталог, дёшево).
 * Уровень 2: модель зовёт load_skill(id) → [instructions] попадает в контекст.
 * Уровень 3: внутри инструкции — ссылки на ресурсы, которые модель читает обычными тулами.
 */
interface Skill {
  val manifest: SkillManifest

  /** Полный текст инструкции (SKILL.md). Загружается лениво, только при активации. */
  suspend fun instructions(): String
}
