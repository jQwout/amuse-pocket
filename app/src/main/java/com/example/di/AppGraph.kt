package com.example.di

import android.app.Application
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
