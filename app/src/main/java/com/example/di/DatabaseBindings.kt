package com.aiopenpocket.di

// @trace agent-memory-05e545

import android.app.Application
import androidx.room.Room
import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.ConversationTagDao
import com.aiopenpocket.agent.core.memory.dao.EpisodeDao
import com.aiopenpocket.agent.core.memory.dao.FactDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import com.aiopenpocket.agent.core.srs.SrsWordDao
import com.aiopenpocket.data.database.AppDatabase
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
        // Pre-release: схему не версионируем — держим единый baseline (AppDatabase version=1). При
        // любом изменении схемы Room просто пересоздаёт базу (fallbackToDestructiveMigration):
        // продакшн-пользователей нет, а локальные dev-данные потерять не жалко. Пошаговые миграции
        // (Migration + addMigrations) заводим с первого релиза, от релизного baseline.
        Room.databaseBuilder(application, AppDatabase::class.java, "ai_open_pocket_db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideConversationDao(database: AppDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideConversationTagDao(database: AppDatabase): ConversationTagDao =
        database.conversationTagDao()

    @Provides
    fun provideMessageDao(database: AppDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideFactDao(database: AppDatabase): FactDao = database.factDao()

    @Provides
    fun provideEpisodeDao(database: AppDatabase): EpisodeDao = database.episodeDao()

    @Provides
    fun provideSrsWordDao(database: AppDatabase): SrsWordDao = database.srsWordDao()
}
