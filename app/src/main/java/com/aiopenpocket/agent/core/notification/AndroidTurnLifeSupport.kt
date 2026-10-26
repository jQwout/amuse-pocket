package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.aiopenpocket.agent.loop.TurnLifeSupport
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The owner of [TurnForegroundService]: counts protected turns and keeps the service exactly while
 * there are more than zero.
 *
 * A counter rather than a set of ids: a new turn of the same conversation starts BEFORE the cancelled
 * previous one reaches its [release], so one conversationId can easily have two holders at once. A set
 * would drop the protection on the first release and leave the new turn bare.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AndroidTurnLifeSupport(private val application: Application) : TurnLifeSupport {

  private val lock = Any()
  private val holders = mutableMapOf<String, Int>()

  @Volatile
  private var protectionLost: ((Set<String>) -> Unit)? = null

  init {
    TurnForegroundService.onSystemTimeout = ::reportProtectionLost
  }

  override suspend fun acquire(conversationId: String): Boolean {
    val needsStart = synchronized(lock) {
      val wasEmpty = holders.isEmpty()
      holders[conversationId] = (holders[conversationId] ?: 0) + 1
      wasEmpty
    }
    if (!needsStart) return true

    // Since API 31 starting an FGS from the background is forbidden (ForegroundServiceStartNotAllowedException).
    // It is neither a programmer error nor a reason to fail the turn — there just will be no guarantee,
    // see the spec, section 6.
    val started = runCatching {
      ContextCompat.startForegroundService(
        application,
        Intent(application, TurnForegroundService::class.java),
      )
    }.isSuccess

    if (!started) synchronized(lock) { decrement(conversationId) }
    return started
  }

  override fun release(conversationId: String) {
    val nowEmpty = synchronized(lock) {
      decrement(conversationId)
      holders.isEmpty()
    }
    if (nowEmpty) {
      application.stopService(Intent(application, TurnForegroundService::class.java))
    }
  }

  override fun onProtectionLost(handler: (Set<String>) -> Unit) {
    protectionLost = handler
  }

  /**
   * The counters are reset here, not in [release]: by this point the system has already taken the
   * service, and without a reset the next [acquire] would see a non-empty map, decide "the service is
   * already up" and hand the turn a false guarantee.
   */
  private fun reportProtectionLost() {
    val protectedIds = synchronized(lock) {
      holders.keys.toSet().also { holders.clear() }
    }
    protectionLost?.invoke(protectedIds)
  }

  private fun decrement(conversationId: String) {
    val left = (holders[conversationId] ?: 0) - 1
    if (left > 0) holders[conversationId] = left else holders.remove(conversationId)
  }
}
