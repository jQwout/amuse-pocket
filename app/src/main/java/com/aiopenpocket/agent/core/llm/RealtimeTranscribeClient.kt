package com.aiopenpocket.agent.core.llm

// @trace meta-voice-transcribe-in-calls-297760

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface RealtimeResult {
  data class Heard(val text: String) : RealtimeResult

  /** Nobody spoke within the wait. */
  data object NoSpeech : RealtimeResult

  /** The session never began (no socket, or Meta refused the first frame): nothing was heard or billed. */
  data class NotStarted(val why: String) : RealtimeResult

  /** The session broke after it began. */
  data class Failed(val why: String) : RealtimeResult

  /** The microphone could not be read; [permission] when RECORD_AUDIO is missing. */
  data class MicFailed(val permission: Boolean) : RealtimeResult
}

/**
 * Muse Voice Transcribe live: the microphone streams to Meta over a WebSocket and the words come back while
 * the person speaks (`wss://api.meta.ai/v1/asr/realtime`, dev.meta.ai/docs/api-reference/voice/realtime, read
 * 4 Nov 2026). One session per phrase, the way a call takes turns.
 *
 * The protocol: the first frame is JSON text with the key (`authorization.accessToken`, "Bearer " included;
 * the HTTP header is ignored here), `audioEncoding: PCM_16KHZ`, the model and `mode: ENDPOINTING`, so Meta
 * itself decides where the phrase ends. Meta answers `{"sessionId": …}`. Then audio goes out as binary frames
 * of raw 16-bit little-endian mono PCM at real time (80 ms frames, as Meta's own example). Back come
 * `transcript` events (CUMULATIVE partials: each replaces the last), `speechStart`/`speechEnd` boundaries and
 * `speechComplete` with the turn's final text, which ends the phrase. `{"type":"endStream"}` closes the input.
 *
 * OkHttp, not Ktor: the app's Ktor engine (Android) cannot open WebSockets, and OkHttp is already in the APK.
 *
 * Spend: $0.18 per hour of audio; what was streamed is recorded, marked estimated (the socket reports no
 * billed duration). Not seen on a live bill. The log never carries the key or the words.
 */
class RealtimeTranscribeClient(
  private val apiKey: () -> String,
  private val usageRecorder: LlmUsageRecorder,
  private val url: String = REALTIME_URL,
  val model: String = VoiceTranscribeClient.MODEL,
  private val okHttp: OkHttpClient = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build(),
) {
  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  fun isConfigured(): Boolean = apiKey().isNotBlank()

  /**
   * Listens for ONE phrase. [audio] streams the microphone, calling its sink with each frame until the sink
   * returns false; [onPartial] gets the words so far. Returns when Meta completes the phrase, nobody spoke,
   * or something broke. Cancelling stops everything.
   */
  suspend fun listenOnce(
    language: String?,
    audio: suspend (sink: (ByteArray) -> Boolean) -> Unit,
    onPartial: (String) -> Unit,
  ): RealtimeResult = coroutineScope { session(this, language, audio, onPartial) }

  private sealed interface Event {
    data object Open : Event
    data class Text(val text: String) : Event
    data class Closed(val code: Int) : Event
    data class Failure(val what: String) : Event
    data class MicError(val permission: Boolean) : Event
  }

  private suspend fun session(
    scope: CoroutineScope,
    language: String?,
    audio: suspend (sink: (ByteArray) -> Boolean) -> Unit,
    onPartial: (String) -> Unit,
  ): RealtimeResult {
    val key = apiKey()
    if (key.isBlank()) return RealtimeResult.NotStarted("no key")
    val events = Channel<Event>(Channel.UNLIMITED)
    val socket = okHttp.newWebSocket(
      Request.Builder().url(url).build(),
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          events.trySend(Event.Open)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          events.trySend(Event.Text(text))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          events.trySend(Event.Closed(code))
          webSocket.close(1000, null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          events.trySend(Event.Failure(response?.code?.let { "HTTP $it" } ?: t.javaClass.simpleName))
        }
      },
    )
    val sentMs = AtomicLong(0)
    val streaming = AtomicBoolean(true)
    // The microphone opens at once, not after Meta's ack: the screen says "listening" from the start, and a
    // person who answers quickly would otherwise lose the first words to the 0.3-1 s handshake. Frames are
    // held until the session is acknowledged, then sent in order ahead of the live ones.
    val gate = Any()
    val held = ArrayList<ByteArray>()
    var live = false
    val pump: Job = scope.launch {
      try {
        audio { frame ->
          val more = streaming.get()
          if (more) {
            synchronized(gate) {
              if (live) {
                socket.send(frame.toByteString())
                sentMs.addAndGet(FRAME_MS)
              } else if (held.size < MAX_HELD_FRAMES) {
                held.add(frame)
              }
            }
          }
          more
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: SecurityException) {
        events.trySend(Event.MicError(permission = true))
      } catch (e: Exception) {
        events.trySend(Event.MicError(permission = false))
      }
    }
    try {
      val opened = withTimeoutOrNull(HANDSHAKE_MS) { events.receive() }
      if (opened is Event.MicError) return RealtimeResult.MicFailed(opened.permission)
      if (opened !is Event.Open) return RealtimeResult.NotStarted("no socket: ${describe(opened)}")
      socket.send(handshake(key, language))
      val ack = withTimeoutOrNull(HANDSHAKE_MS) { events.receive() }
      if (ack is Event.MicError) return RealtimeResult.MicFailed(ack.permission)
      val ackJson = (ack as? Event.Text)?.let { parse(it.text) }
      if (ackJson == null || ackJson.string("type") != null || ackJson.string("sessionId") == null) {
        return RealtimeResult.NotStarted("handshake refused: ${ackJson?.string("message") ?: describe(ack)}")
      }
      synchronized(gate) {
        held.forEach { frame ->
          socket.send(frame.toByteString())
          sentMs.addAndGet(FRAME_MS)
        }
        held.clear()
        live = true
      }

      val startedAt = System.currentTimeMillis()
      var spoke = false
      var lastWords = ""
      while (true) {
        val limit = if (spoke) MAX_PHRASE_MS else NO_SPEECH_MS
        val event = withTimeoutOrNull(limit - (System.currentTimeMillis() - startedAt)) { events.receive() }
          ?: return if (lastWords.isNotBlank()) RealtimeResult.Heard(lastWords) else RealtimeResult.NoSpeech
        when (event) {
          is Event.Text -> {
            val message = parse(event.text) ?: continue
            when (message.string("type")) {
              "transcript" -> {
                val words = message.string("transcript").orEmpty().trim()
                if (words.isNotEmpty()) {
                  spoke = true
                  lastWords = words
                  onPartial(words)
                }
                if ((message["final"] as? JsonPrimitive)?.booleanOrNull == true && words.isNotEmpty()) {
                  return RealtimeResult.Heard(words)
                }
              }
              "speechStart" -> spoke = true
              "speechComplete" -> {
                val words = message.string("transcript")?.trim().orEmpty().ifEmpty { lastWords }
                if (words.isNotBlank()) return RealtimeResult.Heard(words)
                // A turn of noise with no words: keep listening in the same session.
                spoke = false
              }
              "error" -> return RealtimeResult.Failed(message.string("message") ?: "error")
            }
          }
          is Event.Closed ->
            return if (lastWords.isNotBlank()) RealtimeResult.Heard(lastWords) else RealtimeResult.Failed("closed ${event.code}")
          is Event.Failure ->
            return if (lastWords.isNotBlank()) RealtimeResult.Heard(lastWords) else RealtimeResult.Failed(event.what)
          is Event.MicError -> return RealtimeResult.MicFailed(event.permission)
          Event.Open -> Unit
        }
      }
    } finally {
      streaming.set(false)
      pump.cancel()
      runCatching { socket.send(END_STREAM) }
      socket.close(1000, null)
      val ms = sentMs.get()
      if (ms > 0) withContext(NonCancellable) { recordUsage(ms) }
    }
  }

  private fun handshake(key: String, language: String?): String = buildJsonObject {
    putJsonObject("authorization") { put("accessToken", "Bearer $key") }
    put("audioEncoding", "PCM_16KHZ")
    put("model", model)
    put("mode", "ENDPOINTING")
    put("partialMode", "CUMULATIVE")
    put("emitAudioProgress", false)
    if (language != null) putJsonArray("languageBias") { add(language) }
  }.toString()

  private fun parse(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

  // Never `.jsonPrimitive`: it throws on an object or an array, and a field Meta sends in another shape
  // than expected would crash the call on its first phrase. A field of the wrong shape reads as absent.
  private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

  private fun describe(event: Event?): String = when (event) {
    null -> "timed out"
    is Event.Closed -> "closed ${event.code}"
    is Event.Failure -> event.what
    else -> event.javaClass.simpleName
  }

  private suspend fun recordUsage(audioMs: Long) {
    usageRecorder.record(
      LlmCallUsage(
        provider = LlmProvider.META,
        model = model,
        promptTokens = 0,
        completionTokens = 0,
        costUsd = (audioMs / 1_000) * VoiceTranscribeClient.PRICE_PER_SECOND_USD,
        // The socket reports no billed duration: this is what we streamed.
        estimated = true,
        hadTools = false,
      ),
    )
  }

  companion object {
    const val REALTIME_URL = "wss://api.meta.ai/v1/asr/realtime"
    private const val END_STREAM = """{"type":"endStream"}"""
    private const val FRAME_MS = 80L
    private const val HANDSHAKE_MS = 8_000L
    private const val NO_SPEECH_MS = 8_000L
    private const val MAX_PHRASE_MS = 30_000L

    /** What is held before the ack: the whole handshake wait, 8 s of 80 ms frames (about 256 KB). */
    private const val MAX_HELD_FRAMES = (HANDSHAKE_MS / FRAME_MS).toInt()
  }
}
