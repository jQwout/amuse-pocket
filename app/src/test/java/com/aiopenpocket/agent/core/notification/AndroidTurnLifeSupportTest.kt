package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The refcount is checked through a real Robolectric Application: the service starts once on the first
 * turn and goes away on the last one. The real FGS behavior (process survival, the system onTimeout,
 * deferred notification display) is not reproduced by Robolectric — that is for a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTurnLifeSupportTest {

  private val application = ApplicationProvider.getApplicationContext<Application>()

  private fun startedServiceName(): String? =
    shadowOf(application).nextStartedService?.component?.className

  private fun stoppedServiceName(): String? =
    shadowOf(application).nextStoppedService?.component?.className

  @Test
  fun `первый acquire поднимает сервис, следующие едут на уже поднятом`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)

    assertTrue(lifeSupport.acquire("c1"))
    assertEquals(TurnForegroundService::class.java.name, startedServiceName())

    assertTrue(lifeSupport.acquire("c2"))
    assertNull("второй ход едет на уже поднятом сервисе", startedServiceName())
  }

  @Test
  fun `сервис гаснет только на последнем release`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)
    lifeSupport.acquire("c1")
    lifeSupport.acquire("c2")
    startedServiceName() // drop the record of the start

    lifeSupport.release("c1")
    assertNull("остался ещё один защищённый ход", stoppedServiceName())

    lifeSupport.release("c2")
    assertEquals(TurnForegroundService::class.java.name, stoppedServiceName())
  }

  @Test
  fun `повторный acquire того же диалога считается, а не схлопывается`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)
    lifeSupport.acquire("c1")
    lifeSupport.acquire("c1") // a new turn of the same chat on top of the old one being cancelled
    startedServiceName()

    lifeSupport.release("c1")
    assertNull("старый ход отпустил защиту — новый её ещё держит", stoppedServiceName())

    lifeSupport.release("c1")
    assertEquals(TurnForegroundService::class.java.name, stoppedServiceName())
  }

  @Test
  fun `потеря защиты отдаёт защищённые диалоги и обнуляет счётчики`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)
    var reported: Set<String>? = null
    lifeSupport.onProtectionLost { reported = it }
    lifeSupport.acquire("c1")
    lifeSupport.acquire("c2")
    startedServiceName()

    TurnForegroundService.onSystemTimeout?.invoke()

    assertEquals(setOf("c1", "c2"), reported)
    // The counters are reset: the system has already taken the service, and the next turn must start it again.
    lifeSupport.acquire("c3")
    assertEquals(TurnForegroundService::class.java.name, startedServiceName())
  }
}
