package com.aiopenpocket.di

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.skill.LoadSkillTool
import com.aiopenpocket.agent.core.skill.Skill
import com.aiopenpocket.agent.core.skill.skills.BudgetSkill
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

  // Меташар load_skill регистрируется как обычный Tool (виден агент-лупу).
  @Provides
  @IntoSet
  fun provideLoadSkillTool(tool: LoadSkillTool): Tool = tool
}
