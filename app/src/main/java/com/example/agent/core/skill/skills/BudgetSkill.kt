package com.aiopenpocket.agent.core.skill.skills

import android.app.Application
import com.aiopenpocket.agent.core.skill.AssetSkill
import com.aiopenpocket.agent.core.skill.SkillManifest
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/** Навык учёта личных трат. Тело — assets/skills/budget/SKILL.md. */
@Inject
@SingleIn(AppScope::class)
class BudgetSkill(
  application: Application,
) : AssetSkill(application) {

  override val manifest = SkillManifest(
    id = "budget",
    description = "Учёт личных трат: записать расход, посчитать сумму за период, показать диаграмму.",
    whenToUse = "Пользователь говорит о тратах/расходах/бюджете или просит посчитать, сколько потратил.",
  )
}
