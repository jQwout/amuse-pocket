package com.aiopenpocket.voice.input

sealed interface VoiceInputState {
  data object Idle : VoiceInputState
  data class Listening(val partial: String? = null) : VoiceInputState
  data class Error(val reason: ErrorReason) : VoiceInputState

  enum class ErrorReason {
    Unavailable,
    NoMatch,
    Network,
    Audio,
    Permission,
    Busy,
    Other,
  }
}
