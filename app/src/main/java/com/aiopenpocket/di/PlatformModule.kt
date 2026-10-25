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

/** Android-реализации портов доменного слоя (лог, ассеты). */
@ContributesTo(AppScope::class)
@BindingContainer
object PlatformModule {

  /**
   * `d` пишет только в debug-сборке. Отладочные строки цикла несут данные пользователя — превью
   * аргументов и результатов тулов (текст заметок, события календаря), начало его сообщения, — а
   * logcat release-сборки читают adb, баг-репорты и логгеры производителей. `w`/`e` остаются во всех
   * сборках: это сбои, без них поддержке не за что зацепиться.
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
