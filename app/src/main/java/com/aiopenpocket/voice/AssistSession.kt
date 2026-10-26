package com.aiopenpocket.voice

// @trace voice-input-1a0dfd

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.aiopenpocket.MainActivity

/**
 * The voice assistant session.
 *
 * We do not show an assistant overlay of our own — instead, when invoked, we open the app on a new chat
 * right away and hide the session.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        openNewChat()
        // Remove the (empty) session UI right away — the user sees only our app.
        hide()
    }

    private fun openNewChat() {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_NEW_CHAT
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        // startAssistantActivity starts an activity in the assistant's context (it works even over the lock
        // screen), but it was added only in API 26. On 24–25 (minSdk=24) it is missing — we fall back to
        // a normal start: the intent already has FLAG_ACTIVITY_NEW_TASK, so it starts from the session's
        // context.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startAssistantActivity(intent)
        } else {
            context.startActivity(intent)
        }
    }
}
