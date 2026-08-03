package com.aiopenpocket.di

// @trace app-settings-beb470

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.aiopenpocket.agent.core.identity.userPreferencesDataStore
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object UserPreferencesModule {

  @Provides
  fun provideContext(application: Application): Context = application.applicationContext

  /**
   * Единственный на процесс DataStore пользовательских настроек. Явный биндинг (а не обращение к
   * делегату внутри репозитория) нужен, чтобы в тестах на его место вставал изолированный файл —
   * см. KDoc [com.aiopenpocket.agent.core.identity.UserPreferencesRepository].
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideUserPreferencesDataStore(application: Application): DataStore<Preferences> =
    application.userPreferencesDataStore
}
