package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1

import android.app.Application
import android.util.Log
import com.aiopenpocket.BuildConfig
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.prompt.AssetSource
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Android implementations of the domain layer's ports (log, assets). */
@ContributesTo(AppScope::class)
@BindingContainer
object PlatformModule {

  /**
   * `d` writes only in the debug build. The loop's debug lines carry user data — previews of tool
   * arguments and results (note text, calendar events), the start of their message — and a release
   * build's logcat is read by adb, bug reports and vendor loggers. `w`/`e` stay in every build: those
   * are failures, and without them support has nothing to go on.
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideAgentLog(): AgentLog = object : AgentLog {
    override fun d(tag: String, message: String) {
      if (BuildConfig.DEBUG) Log.d(tag, message)
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
