package com.example

import android.app.Application
import com.example.di.AppGraph
import dev.zacsweers.metro.createGraphFactory

class AiOpenPocketApp : Application() {

    lateinit var appGraph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        appGraph = createGraphFactory<AppGraph.Factory>().create(this)
    }
}
