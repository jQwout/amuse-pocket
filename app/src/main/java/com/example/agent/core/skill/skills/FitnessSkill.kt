package com.aiopenpocket.agent.core.skill.skills

import android.app.Application
import com.aiopenpocket.agent.core.skill.AssetSkill
import com.aiopenpocket.agent.core.skill.SkillManifest
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/** Навык спорта/тренировок/шагомера. Тело — assets/skills/fitness/SKILL.md. */
@Inject
@SingleIn(AppScope::class)
class FitnessSkill(
  application: Application,
) : AssetSkill(application) {

  override val manifest = SkillManifest(
    id = "fitness",
    description = "Спорт: график тренировок, дневник занятий, подсчёт шагов через системный шагомер и сводки за неделю.",
    whenToUse = "Пользователь говорит о тренировке, спорте, шагах, активности, режиме занятий или просит спланировать неделю.",
  )
}
