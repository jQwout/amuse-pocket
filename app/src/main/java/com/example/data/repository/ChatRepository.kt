package com.example.data.repository

import com.example.data.database.ChatDao
import com.example.data.database.ChatMessage
import com.example.data.database.UserCookie
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    val allMessages: Flow<List<ChatMessage>> = chatDao.getAllMessages()

    fun getCookieFlow(): Flow<UserCookie?> = chatDao.getCookieFlow()

    suspend fun getCookie(): UserCookie? = chatDao.getCookie()

    suspend fun saveCookie(cookieValue: String) {
        chatDao.insertCookie(UserCookie(cookieValue = cookieValue))
    }

    suspend fun clearCookie() {
        chatDao.clearCookie()
    }

    suspend fun insertMessage(message: ChatMessage): Long {
        return chatDao.insertMessage(message)
    }

    suspend fun clearMessages() {
        chatDao.clearAllMessages()
    }
}
