package com.aiopenpocket.di

import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.tools.AlarmTool
import com.aiopenpocket.agent.tools.CalendarTool
import com.aiopenpocket.agent.tools.DatabaseQueryTool
import com.aiopenpocket.agent.tools.EmailTool
import com.aiopenpocket.agent.tools.ExternalFilesTool
import com.aiopenpocket.agent.tools.FileTools
import com.aiopenpocket.agent.tools.MemoryTools
import com.aiopenpocket.agent.tools.NotificationsTool
import com.aiopenpocket.agent.tools.RenderCardTool
import com.aiopenpocket.agent.tools.SmsTool
import com.aiopenpocket.agent.tools.StepCounterTool
import com.aiopenpocket.agent.tools.WorkManagerTool
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
object AgentToolsModule {

  @Provides
  @IntoSet
  fun provideFileTools(tool: FileTools): Tool = tool

  @Provides
  @IntoSet
  fun provideMemoryTools(tool: MemoryTools): Tool = tool

  @Provides
  @IntoSet
  fun provideRenderCardTool(tool: RenderCardTool): Tool = tool

  @Provides
  @IntoSet
  fun provideNotificationsTool(tool: NotificationsTool): Tool = tool

  @Provides
  @IntoSet
  fun provideExternalFilesTool(tool: ExternalFilesTool): Tool = tool

  @Provides
  @IntoSet
  fun provideDatabaseQueryTool(tool: DatabaseQueryTool): Tool = tool

  @Provides
  @IntoSet
  fun provideWorkManagerTool(tool: WorkManagerTool): Tool = tool

  @Provides
  @IntoSet
  fun provideCalendarTool(tool: CalendarTool): Tool = tool

  @Provides
  @IntoSet
  fun provideAlarmTool(tool: AlarmTool): Tool = tool

  @Provides
  @IntoSet
  fun provideEmailTool(tool: EmailTool): Tool = tool

  @Provides
  @IntoSet
  fun provideSmsTool(tool: SmsTool): Tool = tool

  @Provides
  @IntoSet
  fun provideStepCounterTool(tool: StepCounterTool): Tool = tool
}
