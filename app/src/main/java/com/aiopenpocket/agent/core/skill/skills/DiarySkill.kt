package com.aiopenpocket.agent.core.skill.skills

// @trace agent-skills-7189cc

import android.app.Application
import com.aiopenpocket.agent.core.skill.AssetSkill
import com.aiopenpocket.agent.core.skill.SkillManifest
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/** Навык личного дневника. Тело — assets/skills/diary/SKILL.md. */
@Inject
@SingleIn(AppScope::class)
class DiarySkill(
  application: Application,
) : AssetSkill(application) {

  override val manifest = SkillManifest(
    id = "diary",
    description = "Личный дневник: записать, найти запись, тёплая сводка недели с динамикой настроения.",
    whenToUse = "Пользователь хочет записать что-то о дне, поделиться мыслями, посмотреть прошлые записи или подвести итоги недели.",
  )
}
