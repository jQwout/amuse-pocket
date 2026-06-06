package com.example.di

import android.app.Application
import androidx.room.Room
import com.example.data.database.AppDatabase
import com.example.data.database.ChatDao
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideDatabase(application: Application): AppDatabase =
        Room.databaseBuilder(
            application,
            AppDatabase::class.java,
            "queen_chat_db",
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideChatDao(database: AppDatabase): ChatDao = database.chatDao()
}
