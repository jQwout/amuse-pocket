package com.aiopenpocket.agent.core.skill.skills

// @trace agent-skills-7189cc

import android.app.Application
import com.aiopenpocket.agent.core.skill.AssetSkill
import com.aiopenpocket.agent.core.skill.SkillManifest
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/** Навык изучения иностранных языков. Тело — assets/skills/languages/SKILL.md. */
@Inject
@SingleIn(AppScope::class)
class LanguagesSkill(
  application: Application,
) : AssetSkill(application) {

  override val manifest = SkillManifest(
    id = "languages",
    description = "Изучение иностранных языков: карточки слов с SRS, объяснение грамматики и имитация живого диалога на изучаемом языке.",
    whenToUse = "Пользователь учит язык, просит слово/перевод, спрашивает грамматику, хочет потренировать диалог или назначить ежедневную практику.",
  )
}
