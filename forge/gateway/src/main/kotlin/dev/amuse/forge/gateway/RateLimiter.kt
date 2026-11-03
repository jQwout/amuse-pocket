package dev.amuse.forge.gateway

// @trace skill-market-5d1e7a

/**
 * A fixed-window counter per key (usually the client IP): at most [limit] hits per [windowMillis].
 * In memory, so a restart forgets it, which is fine for abuse brakes on one machine.
 */
class RateLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Window(val startedAt: Long, val hits: Int)

    private val windows = HashMap<String, Window>()

    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val t = now()
        if (windows.size > MAX_KEYS) windows.entries.removeIf { t - it.value.startedAt >= windowMillis }
        val current = windows[key]?.takeIf { t - it.startedAt < windowMillis } ?: Window(t, 0)
        if (current.hits >= limit) return false
        windows[key] = current.copy(hits = current.hits + 1)
        return true
    }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
