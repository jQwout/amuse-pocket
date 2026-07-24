package com.aiopenpocket.di

import android.app.Application
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.workers.HeartbeatRunner
import com.aiopenpocket.data.repository.ThemeRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {

    val userPreferencesRepository: UserPreferencesRepository
    val themeRepository: ThemeRepository
    val heartbeatRunner: HeartbeatRunner

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
