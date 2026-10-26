package com.aiopenpocket.voice

// @trace voice-input-1a0dfd

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * The factory of assistant sessions. The system binds this service and asks it to create a session every
 * time the user invokes the assistant (a long press on home, a gesture, the power button, etc.).
 */
class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistSession(this)
}
