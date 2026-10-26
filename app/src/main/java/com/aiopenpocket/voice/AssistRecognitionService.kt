package com.aiopenpocket.voice

// @trace voice-input-1a0dfd

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * A minimal RecognitionService.
 *
 * Declaring a speech recognition service is required to register a VoiceInteractionService (the
 * recognitionService attribute in the xml). No real recognition happens here — the assistant only opens
 * a new chat, so any request gets a "not supported" error.
 */
class AssistRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
