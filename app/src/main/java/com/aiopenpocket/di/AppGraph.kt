package com.aiopenpocket.di

import android.app.Application
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.plan.execution.PlanRecovery
import com.aiopenpocket.agent.workers.HeartbeatRunner
import com.aiopenpocket.data.repository.ThemeRepository
import dev.ketoy.runtime.KetoyRuntime
import dev.ketoy.runtime.bundle.KetoyBundleLoader
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.viewmodel.ViewModelGraph

// @trace cloud-forged-screens-40d702

@DependencyGraph(AppScope::class)
interface AppGraph : ViewModelGraph {

    val userPreferencesRepository: UserPreferencesRepository
    val themeRepository: ThemeRepository
    val heartbeatRunner: HeartbeatRunner
    val planRecovery: PlanRecovery

    /** Ketoy host runtime and loader, handed to Compose once in `MainActivity` as composition locals. */
    val ketoyRuntime: KetoyRuntime
    val ketoyBundleLoader: KetoyBundleLoader

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides application: Application): AppGraph
    }
}
