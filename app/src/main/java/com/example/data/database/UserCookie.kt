package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_cookies")
data class UserCookie(
    @PrimaryKey val id: String = "session_cookie",
    val cookieValue: String,
    val timestamp: Long = System.currentTimeMillis()
)
