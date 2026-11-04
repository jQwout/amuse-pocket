package com.aiopenpocket.voice.call

// @trace meta-voice-transcribe-in-calls-297760

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Records ONE spoken phrase from the microphone and hands it back as a WAV file, for a speech model that
 * takes a recording rather than a live stream (Meta's Voice Transcribe).
 *
 * Android's recognizer decides on its own where a phrase ends; a recording needs that decision made here.
 * The rule is plain loudness: the first [NOISE_FRAMES] frames measure the room, speech is anything clearly
 * above it for a few frames in a row, and the phrase ends after [END_SILENCE_MS] of quiet following speech.
 * [PRE_ROLL_MS] of audio before the detected start is kept, so the first syllable is not clipped. No speech
 * within [NO_SPEECH_MS] returns null ("nobody said anything"), and a phrase is cut at [MAX_PHRASE_MS].
 *
 * 16 kHz mono 16-bit PCM: what speech models are trained on, and a 10-second phrase stays about 320 KB.
 */
class PhraseRecorder {

  class Phrase(val wav: ByteArray, val durationMs: Long)

  /**
   * Blocks (on [Dispatchers.IO]) until a phrase is over, reporting the loudness 0..1 every frame through
   * [onLevel]. Cancelling the coroutine stops the recording. Throws [SecurityException] without the
   * microphone permission and [IllegalStateException] when the microphone cannot be opened.
   */
  @SuppressLint("MissingPermission") // The call asks for RECORD_AUDIO before it ever listens.
  suspend fun recordPhrase(onLevel: (Float) -> Unit): Phrase? = withContext(Dispatchers.IO) {
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    check(minBuffer > 0) { "no audio input at $SAMPLE_RATE Hz" }
    val record = AudioRecord(
      MediaRecorder.AudioSource.VOICE_RECOGNITION,
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_16BIT,
      maxOf(minBuffer, FRAME_SAMPLES * 2 * 4),
    )
    try {
      check(record.state == AudioRecord.STATE_INITIALIZED) { "the microphone could not be opened" }
      record.startRecording()
      val frame = ShortArray(FRAME_SAMPLES)
      val preRoll = ArrayDeque<ShortArray>()
      val speech = ByteArrayOutputStream()
      var noise = 0.0
      var frames = 0
      var loudRun = 0
      var started = false
      var quietMs = 0L
      var spokenMs = 0L
      while (true) {
        ensureActive()
        val read = record.read(frame, 0, FRAME_SAMPLES)
        if (read <= 0) continue
        frames++
        val rms = rms(frame, read)
        onLevel((rms / LEVEL_FULL_SCALE).toFloat().coerceIn(0f, 1f))

        if (frames <= NOISE_FRAMES) {
          noise = maxOf(noise, rms)
          continue
        }
        val threshold = maxOf(noise * NOISE_FACTOR, MIN_SPEECH_RMS)
        val loud = rms > threshold
        if (!started) {
          preRoll.addLast(frame.copyOf(read))
          if (preRoll.size > PRE_ROLL_MS / FRAME_MS) preRoll.removeFirst()
          loudRun = if (loud) loudRun + 1 else 0
          if (loudRun >= START_FRAMES) {
            started = true
            preRoll.forEach { speech.write(it.toLittleEndianBytes()) }
            spokenMs = preRoll.size * FRAME_MS
            preRoll.clear()
          } else if (frames * FRAME_MS > NO_SPEECH_MS) {
            return@withContext null
          }
          continue
        }
        speech.write(frame.copyOf(read).toLittleEndianBytes())
        spokenMs += FRAME_MS
        quietMs = if (loud) 0 else quietMs + FRAME_MS
        if (quietMs >= END_SILENCE_MS || spokenMs >= MAX_PHRASE_MS) break
      }
      onLevel(0f)
      val pcm = speech.toByteArray()
      Phrase(wav = wav(pcm), durationMs = spokenMs)
    } finally {
      runCatching { record.stop() }
      record.release()
    }
  }

  private fun rms(samples: ShortArray, count: Int): Double {
    var sum = 0.0
    for (i in 0 until count) sum += samples[i].toDouble() * samples[i]
    return sqrt(sum / count)
  }

  private fun ShortArray.toLittleEndianBytes(): ByteArray {
    val buffer = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
    forEach { buffer.putShort(it) }
    return buffer.array()
  }

  companion object {
    const val SAMPLE_RATE = 16_000
    private const val FRAME_MS = 20L
    private const val FRAME_SAMPLES = (SAMPLE_RATE * FRAME_MS / 1000).toInt()

    /** The first 300 ms measure the room. */
    private const val NOISE_FRAMES = 15
    private const val NOISE_FACTOR = 2.5
    /** A floor for a very quiet room, so breathing does not count as speech. */
    private const val MIN_SPEECH_RMS = 450.0
    /** 80 ms above the threshold in a row starts a phrase; a click does not. */
    private const val START_FRAMES = 4
    private const val PRE_ROLL_MS = 300L
    private const val END_SILENCE_MS = 1_100L
    private const val NO_SPEECH_MS = 8_000L
    private const val MAX_PHRASE_MS = 30_000L
    /** The RMS shown as a full orb: loud speech close to the phone. */
    private const val LEVEL_FULL_SCALE = 6_000.0

    /** A 16-bit mono PCM WAV: the 44-byte RIFF header, then [pcm]. */
    internal fun wav(pcm: ByteArray): ByteArray {
      val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36 + pcm.size)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1) // PCM
        putShort(1) // mono
        putInt(SAMPLE_RATE)
        putInt(SAMPLE_RATE * 2) // byte rate
        putShort(2) // block align
        putShort(16) // bits per sample
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcm.size)
      }
      return header.array() + pcm
    }
  }
}
