package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1

import android.app.Application
import android.util.Log
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.prompt.AssetSource
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Android-реализации портов доменного слоя (лог, ассеты). */
@ContributesTo(AppScope::class)
@BindingContainer
object PlatformModule {

  @Provides
  @SingleIn(AppScope::class)
  fun provideAgentLog(): AgentLog = object : AgentLog {
    override fun d(tag: String, message: String) {
      Log.d(tag, message)
    }

    override fun w(tag: String, message: String) {
      Log.w(tag, message)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
      Log.e(tag, message, error)
    }
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideAssetSource(application: Application): AssetSource = AssetSource { path ->
    runCatching { application.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
  }
}
