package com.aiopenpocket.voice

// @trace voice-input-1a0dfd

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Фабрика сессий помощника. Система биндит этот сервис и просит создать
 * сессию каждый раз, когда пользователь вызывает помощника
 * (долгое нажатие home, жест, кнопка питания и т.д.).
 */
class AssistSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        AssistSession(this)
}
