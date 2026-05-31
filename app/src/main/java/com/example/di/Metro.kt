package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.data.database.AppDatabase
import com.example.data.network.GeminiClient
import com.example.data.repository.ChatRepository
import com.example.navigation.NavigationBridge

object Metro {
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            appContext,
            AppDatabase::class.java,
            "queen_chat_db"
        )
        .fallbackToDestructiveMigration()
        .build()
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(database.chatDao())
    }

    val geminiClient: GeminiClient by lazy {
        GeminiClient()
    }

    val navigationBridge: NavigationBridge
        get() = NavigationBridge
}
