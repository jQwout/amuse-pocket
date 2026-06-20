package com.aiopenpocket

import android.app.Application
import com.aiopenpocket.di.AppGraph
import dev.zacsweers.metro.createGraphFactory

class AiOpenPocketApp : Application() {

    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = createGraphFactory<AppGraph.Factory>().create(this)
    }
}
