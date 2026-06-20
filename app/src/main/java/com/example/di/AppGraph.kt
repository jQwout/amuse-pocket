package com.aiopenpocket.di

import android.app.Application
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {

    val userPreferencesRepository: UserPreferencesRepository

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
