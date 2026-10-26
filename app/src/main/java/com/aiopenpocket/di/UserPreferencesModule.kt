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
   * The one-per-process DataStore of user preferences. An explicit binding (rather than reaching for
   * the delegate inside the repository) lets tests put an isolated file in its place — see the KDoc of
   * [com.aiopenpocket.agent.core.identity.UserPreferencesRepository].
   */
  @Provides
  @SingleIn(AppScope::class)
  fun provideUserPreferencesDataStore(application: Application): DataStore<Preferences> =
    application.userPreferencesDataStore
}
