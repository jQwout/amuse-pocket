package com.aiopenpocket.voice

// @trace voice-input-1a0dfd

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.aiopenpocket.MainActivity

/**
 * Сессия голосового помощника.
 *
 * Мы не показываем собственный оверлей помощника — вместо этого при вызове
 * сразу открываем приложение на новом чате и скрываем сессию.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        openNewChat()
        // Сразу убираем (пустой) UI сессии — пользователь видит только наше приложение.
        hide()
    }

    private fun openNewChat() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_NEW_CHAT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // startAssistantActivity запускает активити в контексте помощника (работает в том числе
        // поверх экрана блокировки), но добавлен только в API 26. На 24–25 (minSdk=24) его нет —
        // падаем на обычный запуск: intent уже с FLAG_ACTIVITY_NEW_TASK, поэтому стартует из
        // контекста сессии.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startAssistantActivity(intent)
        } else {
            context.startActivity(intent)
        }
    }
}
