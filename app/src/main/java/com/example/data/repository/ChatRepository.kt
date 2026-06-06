package com.example.data.repository

import android.app.Application
import com.example.data.database.ChatDao
import com.example.data.database.ChatMessage
import com.example.data.database.UserCookie
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.example.di.AppScope
import kotlinx.coroutines.flow.Flow

@Inject
@SingleIn(AppScope::class)
class ChatRepository(
    private val chatDao: ChatDao,
    application: Application,
) {

    private val sharedPrefs by lazy {
        application.getSharedPreferences("ai_pocket_preferences", Application.MODE_PRIVATE)
    }

    fun getCustomApiKey(): String {
        return sharedPrefs.getString("custom_nvidia_api_key", "") ?: ""
    }

    fun saveCustomApiKey(key: String) {
        sharedPrefs.edit().putString("custom_nvidia_api_key", key).apply()
    }

    fun getPersonaId(): String {
        return sharedPrefs.getString("selected_persona_id", "sharp_partner") ?: "sharp_partner"
    }

    fun savePersonaId(personaId: String) {
        sharedPrefs.edit().putString("selected_persona_id", personaId).apply()
    }

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
