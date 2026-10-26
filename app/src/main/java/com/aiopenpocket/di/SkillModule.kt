package com.aiopenpocket.di

// @trace agent-skills-7189cc

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

  // Skills are a Set<Skill> multibinding, consumed by SkillRegistry.
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

  // The load_skill meta tool is registered as an ordinary Tool (visible to the agent loop).
  @Provides
  @IntoSet
  fun provideLoadSkillTool(tool: LoadSkillTool): Tool = tool
}
