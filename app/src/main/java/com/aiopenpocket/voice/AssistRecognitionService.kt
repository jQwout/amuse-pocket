package com.aiopenpocket.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Минимальный RecognitionService.
 *
 * Объявление сервиса распознавания речи обязательно для регистрации
 * VoiceInteractionService (атрибут recognitionService в xml). Реальное
 * распознавание здесь не выполняется — помощник лишь открывает новый чат,
 * поэтому на любой запрос мы возвращаем ошибку "не поддерживается".
 */
class AssistRecognitionService : RecognitionService() {

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        runCatching { listener?.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY) }
    }

    override fun onCancel(listener: Callback?) = Unit

    override fun onStopListening(listener: Callback?) = Unit
}
