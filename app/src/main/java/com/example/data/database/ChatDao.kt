package com.example.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM user_cookies WHERE id = 'session_cookie' LIMIT 1")
    suspend fun getCookie(): UserCookie?

    @Query("SELECT * FROM user_cookies WHERE id = 'session_cookie' LIMIT 1")
    fun getCookieFlow(): Flow<UserCookie?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCookie(cookie: UserCookie)

    @Query("DELETE FROM user_cookies")
    suspend fun clearCookie()
}
