package com.aiopenpocket.voice

import android.content.Context
import android.content.Intent
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
        // startAssistantActivity запускает активити в контексте помощника
        // (работает в том числе поверх экрана блокировки, если это разрешено).
        startAssistantActivity(intent)
    }
}
