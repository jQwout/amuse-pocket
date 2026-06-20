package com.aiopenpocket.agent.core.skill

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Реестр навыков (зеркало ToolRegistry). Навыки приходят через DI-мультибиндинг Set<Skill>.
 */
@SingleIn(AppScope::class)
class SkillRegistry @Inject constructor(
  skills: Set<@JvmSuppressWildcards Skill>,
) {
  private val skillMap = skills.associateBy { it.manifest.id }

  fun get(id: String): Skill? = skillMap[id]

  fun ids(): Set<String> = skillMap.keys

  /**
   * Дешёвый каталог для системного промпта (уровень 1) — только метаданные, без чтения ассетов.
   * Пусто, если навыков нет (тогда секция в промпт не добавляется).
   */
  fun catalog(): String =
    skillMap.values.joinToString("\n") { s ->
      "- ${s.manifest.id}: ${s.manifest.description} (когда: ${s.manifest.whenToUse})"
    }
}
