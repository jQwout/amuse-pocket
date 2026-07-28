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
 * Владелец [TurnForegroundService]: считает защищённые ходы и держит сервис ровно пока их больше нуля.
 *
 * Счётчик, а не множество id: новый ход того же диалога стартует ДО того, как отменённый предыдущий
 * дойдёт до своего [release], поэтому по одному conversationId вполне бывает два одновременных
 * держателя. Множество сняло бы защиту на первом же release и оставило новый ход голым.
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

    // С API 31 старт FGS из фона запрещён (ForegroundServiceStartNotAllowedException). Это не
    // ошибка программиста и не повод валить ход — просто гарантии не будет, см. спеку, раздел 6.
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
   * Счётчики обнуляются здесь, а не в [release]: сервис к этому моменту система уже забрала, и без
   * обнуления следующий [acquire] увидел бы непустую мапу, решил «сервис уже поднят» и вернул бы
   * ходу ложную гарантию.
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
