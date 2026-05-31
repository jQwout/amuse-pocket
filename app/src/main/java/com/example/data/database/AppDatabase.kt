package com.example.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UserCookie::class, ChatMessage::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
