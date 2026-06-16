package com.aiopenpocket.voice

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
