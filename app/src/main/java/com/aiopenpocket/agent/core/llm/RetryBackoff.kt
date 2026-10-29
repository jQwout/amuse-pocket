package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1

import kotlin.random.Random

/**
 * The backoff Meta documents for transient errors (dev.meta.ai/docs/error-handling): start at about a
 * second, double on every attempt, add a little jitter, and honour `Retry-After` when the server asks
 * for longer. One implementation for every Meta caller — the chat client and [SegmentationClient] —
 * so the two cannot drift apart on what "wait" means.
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
}
