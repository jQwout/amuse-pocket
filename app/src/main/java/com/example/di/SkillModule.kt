package com.aiopenpocket.di

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.skill.LoadSkillTool
import com.aiopenpocket.agent.core.skill.Skill
import com.aiopenpocket.agent.core.skill.skills.BudgetSkill
import com.aiopenpocket.agent.core.skill.skills.DiarySkill
import com.aiopenpocket.agent.core.skill.skills.FitnessSkill
import com.aiopenpocket.agent.core.skill.skills.LanguagesSkill
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
object SkillModule {

  // Навыки — мультибиндинг Set<Skill>, потребляется SkillRegistry.
  @Provides
  @IntoSet
  fun provideBudgetSkill(skill: BudgetSkill): Skill = skill

  @Provides
  @IntoSet
  fun provideDiarySkill(skill: DiarySkill): Skill = skill

  @Provides
  @IntoSet
  fun provideLanguagesSkill(skill: LanguagesSkill): Skill = skill

  @Provides
  @IntoSet
  fun provideFitnessSkill(skill: FitnessSkill): Skill = skill

  // Меташар load_skill регистрируется как обычный Tool (виден агент-лупу).
  @Provides
  @IntoSet
  fun provideLoadSkillTool(tool: LoadSkillTool): Tool = tool
}
