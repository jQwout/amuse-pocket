package com.aiopenpocket.voice.call

// @trace voice-call-mode-54cb49

/** Where the call is, as the person sees it. */
enum class CallPhase { LISTENING, THINKING, SPEAKING, WAITING_APPROVAL }

/**
 * Who has the floor in a call. Half duplex on purpose: the microphone is on only while nobody else is
 * talking or about to, otherwise the recognizer hears the phone's own voice and answers itself.
 *
 * The hard rule is when to hand the floor back. The reply is read sentence by sentence while the model
 * is still writing, so the voice can fall silent between two sentences with the turn still running:
 * that is still the agent's floor ([CallPhase.THINKING]), not the person's. Only a finished turn with
 * nothing left to read, or the person cutting the reply off, gives it back.
 */
data class CallLoop(
  val turnRunning: Boolean = false,
  val speaking: Boolean = false,
  /** The person cut the current reply off: the rest of it is not read, and the floor is theirs. */
  val cutOff: Boolean = false,
  /** The agent waits for a yes or no on a risky action; the call shows the card and does not listen. */
  val approvalPending: Boolean = false,
) {
  val phase: CallPhase
    get() = when {
      speaking -> CallPhase.SPEAKING
      approvalPending -> CallPhase.WAITING_APPROVAL
      turnRunning && !cutOff -> CallPhase.THINKING
      else -> CallPhase.LISTENING
    }

  val wantsMic: Boolean get() = phase == CallPhase.LISTENING

  /** Whether more of the current reply may still be read aloud. */
  val readsReply: Boolean get() = !cutOff

  fun userSaid() = copy(turnRunning = true, speaking = false, cutOff = false)

  fun turnEnded() = copy(turnRunning = false)

  /** Mirrors the speech engine: true while anything is queued or being read. */
  fun speaking(now: Boolean) = copy(speaking = now)

  fun cutOff() = copy(speaking = false, cutOff = true)

  fun approval(pending: Boolean) = copy(approvalPending = pending)
}
