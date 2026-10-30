package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import android.util.Log
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * The backoff Meta documents for transient errors (dev.meta.ai/docs/error-handling): start at about a
 * second, double on every attempt, add a little jitter, and honour `Retry-After` when the server asks
 * for longer. One implementation for every Meta caller — the chat client, [SegmentationClient],
 * [ImageGenClient] and [VideoUnderstandingClient] — so they cannot drift apart on what "wait" means.
 */
internal object RetryBackoff {
  private const val BASE_BACKOFF_MS = 1_000L
  private const val JITTER_MS = 500L

  /**
   * How long to wait before attempt [attempt] + 1. `Retry-After` in seconds wins when it is longer than
   * the plain backoff (1 s, 2 s, …); a small random jitter on top, so a burst of retries does not arrive
   * together. An HTTP-date `Retry-After` is not parsed: the plain backoff stands in.
   */
  fun delayMs(attempt: Int, retryAfter: String?, random: Random): Long {
    val backoff = BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 10)
    val asked = retryAfter?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let { it * 1_000L }
    return maxOf(backoff, asked ?: 0L) + random.nextLong(JITTER_MS + 1)
  }

  /** Attempts per call, the first one included. */
  const val MAX_ATTEMPTS = 3

  /** "Come back in a minute" is no use to someone standing on a lesson step: past this, give up. */
  private const val MAX_WAIT_MS = 20_000L

  /**
   * The statuses Meta documents as worth another try for a one-shot (non-streaming) call: 429 and 503.
   * 500 is deliberately NOT here: Meta says a 500 will not go away by repeating the same request.
   */
  fun isTransient(status: Int): Boolean = status == 429 || status == 503

  /**
   * Runs [request] and, while the answer is a transient status ([isTransient]), waits as [delayMs]
   * says and runs it again, up to [MAX_ATTEMPTS]. Returns the last response: a success, a hard error,
   * or the transient one the tries ran out on - the caller decides what that means. A transport
   * failure is not caught here; it propagates, as it would from a single call.
   */
  suspend fun send(random: Random, tag: String, request: suspend () -> HttpResponse): HttpResponse {
    var attempt = 1
    var response = request()
    while (isTransient(response.status.value) && attempt < MAX_ATTEMPTS) {
      val wait = delayMs(attempt, response.headers[HttpHeaders.RetryAfter], random)
      if (wait > MAX_WAIT_MS) {
        Log.w(tag, "HTTP ${response.status.value}: the wait asked for is ${wait}ms - giving up")
        break
      }
      Log.i(tag, "HTTP ${response.status.value}, attempt $attempt of $MAX_ATTEMPTS, waiting ${wait}ms")
      delay(wait)
      attempt++
      response = request()
    }
    return response
  }
}
