package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d

/**
 * Accumulates stream text with a publish-rate gate: toString() copies the whole accumulated text, and
 * the eye needs no more than ~20 updates per second. The clock is injected (monotonic, see TurnModule)
 * — the class is pure and is tested without Android.
 */
class StreamingTextBuffer(
  private val nowMs: () -> Long,
  private val minPublishIntervalMs: Long = DEFAULT_PUBLISH_INTERVAL_MS,
) {
  private val builder = StringBuilder()
  private var lastPublishAt = 0L

  /** Accumulates [delta]; returns a snapshot to publish if the gate lets it through, null otherwise. */
  fun append(delta: String): String? {
    builder.append(delta)
    val now = nowMs()
    if (now - lastPublishAt < minPublishIntervalMs) return null
    lastPublishAt = now
    return builder.toString()
  }

  /** The stream tail that did not pass the gate: a snapshot if anything accumulated, null otherwise. */
  fun flush(): String? = if (builder.isNotEmpty()) builder.toString() else null

  fun reset() {
    builder.setLength(0)
    lastPublishAt = 0L
  }

  companion object {
    /** Minimum interval between stream text publishes (~20 updates per second). */
    const val DEFAULT_PUBLISH_INTERVAL_MS = 50L
  }
}
