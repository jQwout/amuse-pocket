package com.aiopenpocket.voice

// @trace voice-input-1a0dfd
// @trace voice-assistant-mode-a81a99

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.aiopenpocket.ui.assistant.AssistantActivity

/**
 * The voice assistant session.
 *
 * The session's own window stays empty: when invoked, it starts [AssistantActivity], a translucent window
 * over the current app that listens at once and answers in place, and hides itself.
 */
class AssistSession(context: Context) : VoiceInteractionSession(context) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // startAssistantActivity starts the activity in the assistant's context (API 26+, minSdk is 26).
        startAssistantActivity(
            Intent(context, AssistantActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        hide()
    }
}
