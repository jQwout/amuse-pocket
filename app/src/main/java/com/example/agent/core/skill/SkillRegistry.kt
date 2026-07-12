package com.aiopenpocket.agent.core.skill

// @trace agent-skills-7189cc
// @trace user-agent-builder-848153

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Composite реестр навыков (зеркало [com.aiopenpocket.agent.core.agent.AgentResolver]: read-only
 * preset store + Room custom store). Source 1 — built-in [Skill]-бины из DI-мультибиндинга
 * Set<Skill> (read-only, как раньше). Source 2 — [userSkillRepository], agent-owned custom skills
 * из Room. Все методы suspend — Room-часть требует IO.
 *
 * При коллизии id built-in ПОБЕЖДАЕТ: тот же id, что уже занят built-in навыком, никогда не
 * резолвится в custom-запись. На практике коллизии не возникает — [validateUserSkill] запрещает
 * публикацию custom skill с id встроенного навыка, — но резолвинг остаётся детерминированным
 * (built-in wins), а не полагается только на то, что publish был вызван до того, как built-in
 * набор мог измениться.
 */
@SingleIn(AppScope::class)
class SkillRegistry @Inject constructor(
  builtIn: Set<@JvmSuppressWildcards Skill>,
  private val userSkillRepository: UserSkillRepository,
) {
  private val builtInMap = builtIn.associateBy { it.manifest.id }

  suspend fun get(id: String): Skill? = builtInMap[id] ?: userSkillRepository.get(id)

  suspend fun ids(): Set<String> = builtInMap.keys + userSkillRepository.ids()

  /**
   * Дешёвый каталог для системного промпта (уровень 1) — только метаданные, без чтения ассетов.
   * Пусто, если навыков нет (тогда секция в промпт не добавляется). [allowedIds] не null — сужает
   * каталог до навыков конкретного агента (built-in и/или его собственные custom), см.
   * [com.aiopenpocket.agent.core.prompt.PrePromptAssembler] и [LoadSkillTool].
   */
  suspend fun catalog(allowedIds: Set<String>? = null): String {
    // Custom skills whose id collides with a built-in are excluded here too, so the catalog text
    // never advertises an entry that get() would then resolve to the built-in instead.
    val custom = userSkillRepository.getAll().filter { it.manifest.id !in builtInMap.keys }
    return (builtInMap.values + custom)
      .filter { allowedIds == null || it.manifest.id in allowedIds }
      .joinToString("\n") { s ->
        "- ${s.manifest.id}: ${s.manifest.description} (когда: ${s.manifest.whenToUse})"
      }
  }
}
