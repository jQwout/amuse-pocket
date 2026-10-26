package com.aiopenpocket.voice

// @trace voice-input-1a0dfd

import android.service.voice.VoiceInteractionService

/**
 * The entry point for registering the app as the system voice assistant.
 *
 * The service itself shows nothing — all the work happens in the session ([AssistSession]) that the
 * system creates when the assistant is invoked.
 */
class AssistInteractionService : VoiceInteractionService()
