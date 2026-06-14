package com.aiopenpocket.di

import android.app.Application
import android.content.Context
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides

@ContributesTo(AppScope::class)
@BindingContainer
object UserPreferencesModule {

  @Provides
  fun provideContext(application: Application): Context = application.applicationContext
}
