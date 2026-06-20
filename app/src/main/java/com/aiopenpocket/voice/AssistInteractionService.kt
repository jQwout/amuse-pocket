package com.aiopenpocket.voice

import android.service.voice.VoiceInteractionService

/**
 * Точка входа для регистрации приложения как системного голосового помощника.
 *
 * Сам по себе сервис ничего не показывает — вся работа происходит в сессии
 * ([AssistSession]), которую система создаёт при вызове помощника.
 */
class AssistInteractionService : VoiceInteractionService()
