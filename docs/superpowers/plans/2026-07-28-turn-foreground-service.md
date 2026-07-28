# Turn Foreground Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ход агента, начатый с экрана, доезжает до записи ответа даже если пользователь свернул приложение.

**Architecture:** Новый узкий порт `TurnLifeSupport` рядом с `ForegroundState`/`TurnNotifier` в `TurnPorts.kt`; `TurnManager` берёт защиту перед стартом хода и отпускает в `finally`. Android-реализация держит рефкаунт и поднимает `TurnForegroundService` (тип `shortService`). По системному таймауту защищённые ходы валятся с «Повторить» — но только если приложение в фоне.

**Tech Stack:** Kotlin 2.3, Metro DI, kotlinx.coroutines, Android Service (`shortService`), JUnit 4, Robolectric, Gradle, traxer.

## Global Constraints

- Спецификация: `docs/superpowers/specs/2026-07-28-turn-foreground-service-design.md`.
- Trace ID переиспользуется: `fonovye-hody-agenta-dde42d`. Это углубление существующей фичи «Фоновые ходы агента», а не новая — `traxer id` НЕ запускать. Новые файлы получают `// @trace fonovye-hody-agenta-dde42d`.
- Трейлеры коммитов (`Trace:`, `Co-Authored-By:`) писать одним слитным блоком без пустой строки между ними.
- `minSdk = 24`, `targetSdk = 36`. Всё, что новее 24, закрывать `Build.VERSION.SDK_INT`-гейтом или `@RequiresApi`.
- Отсутствие защиты НИКОГДА не валит ход: `acquire == false` → ход исполняется как до этой фичи.
- Своего таймера на API < 34 не заводить: системного потолка там нет.
- Перед коммитом — `traxer check`; stale-warning блессить только после подтверждения rationale.
- Файлы под `app/src/main/java/com/aiopenpocket/...`, тесты — `app/src/test/java/com/aiopenpocket/...`.

## File Map

Новые production-файлы:

```text
app/src/main/java/com/aiopenpocket/agent/core/notification/
  TurnForegroundService.kt      — сам сервис + канал + уведомление
  AndroidTurnLifeSupport.kt     — рефкаунт, старт/стоп сервиса, реализация порта
```

Изменяемые:

```text
app/src/main/java/com/aiopenpocket/agent/loop/TurnPorts.kt        — порт TurnLifeSupport
app/src/main/java/com/aiopenpocket/agent/loop/TurnManager.kt      — acquire/release + реакция на потерю защиты
app/src/main/java/com/aiopenpocket/agent/loop/TurnCompletion.kt   — текст ошибки
app/src/main/java/com/aiopenpocket/di/TurnModule.kt               — прокидка порта
app/src/main/AndroidManifest.xml                                  — разрешения + <service>
app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt  — фейк порта + тесты
traceability.yaml                                                 — rationale фичи
```

---

### Task 1: Порт TurnLifeSupport и захват/освобождение в TurnManager

**Files:**
- Modify: `app/src/main/java/com/aiopenpocket/agent/loop/TurnPorts.kt`
- Modify: `app/src/main/java/com/aiopenpocket/agent/loop/TurnManager.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`

**Interfaces:**
- Produces: `TurnLifeSupport.acquire(conversationId: String): Boolean`, `TurnLifeSupport.release(conversationId: String)`, `TurnLifeSupport.onProtectionLost(handler: (Set<String>) -> Unit)`; новый обязательный параметр конструктора `TurnManager(lifeSupport = ...)`.

- [ ] **Step 1: Добавить фейк порта в тестовый харнесс**

В `TurnManagerTest.kt`, рядом с `RecordingNotifier`:

```kotlin
  private class FakeLifeSupport : TurnLifeSupport {
    /** false — система отказала в защите (типичный случай: старт из фона). */
    var grant = true
    val acquired = mutableListOf<String>()
    val released = mutableListOf<String>()
    private var handler: ((Set<String>) -> Unit)? = null

    override suspend fun acquire(conversationId: String): Boolean {
      if (grant) acquired += conversationId
      return grant
    }

    override fun release(conversationId: String) {
      released += conversationId
    }

    override fun onProtectionLost(handler: (Set<String>) -> Unit) {
      this.handler = handler
    }

    /** Имитирует системный onTimeout сервиса. */
    fun loseProtection(ids: Set<String>) = handler?.invoke(ids)
  }
```

Там же, рядом с `private val notifier = RecordingNotifier()`:

```kotlin
  private val lifeSupport = FakeLifeSupport()
```

и в `TestScope.manager()` добавить аргумент после `notifier = notifier,`:

```kotlin
    lifeSupport = lifeSupport,
```

- [ ] **Step 2: Написать падающие тесты**

Дописать в `TurnManagerTest.kt`:

```kotlin
  @Test
  fun `ход берёт защиту перед стартом и отпускает после завершения`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("ок"))) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(listOf("c1"), lifeSupport.acquired)
    assertEquals(listOf("c1"), lifeSupport.released)
  }

  @Test
  fun `упавший ход тоже отпускает защиту`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("бек лёг")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals(listOf("c1"), lifeSupport.released)
  }

  @Test
  fun `отменённый ход отпускает защиту`() = runTest {
    executor.script = { flow { awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    m.cancel("c1")
    advanceUntilIdle()

    assertEquals(listOf("c1"), lifeSupport.released)
  }

  @Test
  fun `отказ в защите не мешает ходу доехать и не порождает release`() = runTest {
    lifeSupport.grant = false
    executor.script = { flowOf(AgentEvent.Completed(finished("ок"))) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    assertEquals("ок", persistence.rows.last().content)
    assertTrue("release без успешного acquire увёл бы рефкаунт в минус", lifeSupport.released.isEmpty())
  }
```

- [ ] **Step 3: Подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"
```

Expected: FAIL — `TurnLifeSupport` не существует, `manager()` не принимает `lifeSupport`.

- [ ] **Step 4: Добавить порт**

В конец `TurnPorts.kt`:

```kotlin
/**
 * Защита хода от вытеснения процесса, пока приложение в фоне (спека
 * `2026-07-28-turn-foreground-service-design.md`). Отдельный порт, а не вызов Android напрямую,
 * по той же причине, что [ForegroundState]/[TurnNotifier]: [TurnManager] обязан тестироваться
 * без Android.
 */
interface TurnLifeSupport {
  /**
   * Просит защиту для [conversationId]. `false` — система отказала; типичный случай — старт хода,
   * когда приложение уже в фоне (с API 31 стартовать foreground-сервис оттуда нельзя). Ход при
   * этом всё равно исполняется, просто без гарантии — ровно как до появления этой фичи.
   */
  suspend fun acquire(conversationId: String): Boolean

  /** Идемпотентно снимает защиту. Вызывать ТОЛЬКО если [acquire] вернул `true`. */
  fun release(conversationId: String)

  /**
   * Регистрирует реакцию на «система отобрала защиту» (таймаут `shortService`). Аргумент —
   * conversationId, которые на этот момент были защищены. Вызывается один раз, при сборке графа.
   */
  fun onProtectionLost(handler: (Set<String>) -> Unit)
}
```

- [ ] **Step 5: Прокинуть в TurnManager**

В конструкторе `TurnManager`, после `private val notifier: TurnNotifier,`:

```kotlin
  private val lifeSupport: TurnLifeSupport,
```

В `startTurn` заменить тело на:

```kotlin
    // Захват ДО мьютекса: acquire уходит в Android (старт сервиса), держать на нём мьютекс
    // менеджера незачем — он сериализует только подмену job. Ложный acquire не делаем: если
    // система откажет, ход просто пойдёт без защиты.
    val hasProtection = lifeSupport.acquire(conversationId)
    mutex.withLock {
      val session = sessionsFlow.value[conversationId] ?: TurnSession(conversationId, monotonicNowMs).also { created ->
        sessionsFlow.update { it + (conversationId to created) }
      }
      // Гасим прошлый ход СРАЗУ под мьютексом, а join ждём уже внутри нового job. Иначе, если новый
      // job отменят (Stop/clearError) раньше, чем он дойдёт до cancelAndJoin, прошлый ход остался бы
      // недоотменённым и продолжил бы писать ответ в «остановленный» диалог.
      val previous = session.job
      previous?.cancel()
      session.job = scope.launch {
        try {
          previous?.join()
          runTurn(session, userText, mode, planId, budget, persistUserMessage, systemPromptExtra, executionMode)
        } finally {
          // finally, а не хвост runTurn: отмена (Stop, новый ход) обязана отпустить защиту тоже,
          // иначе сервис с уведомлением переживёт ход, ради которого поднимался.
          if (hasProtection) lifeSupport.release(conversationId)
        }
      }
    }
```

- [ ] **Step 6: Подтвердить GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"
```

Expected: PASS, включая четыре новых теста.

- [ ] **Step 7: Коммит**

```bash
git add app/src/main/java/com/aiopenpocket/agent/loop/TurnPorts.kt app/src/main/java/com/aiopenpocket/agent/loop/TurnManager.kt app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt
git commit -m "feat(background-turns): порт TurnLifeSupport и захват защиты на время хода"
```

---

### Task 2: Реакция на потерю защиты, гейт на видимости приложения

**Files:**
- Modify: `app/src/main/java/com/aiopenpocket/agent/loop/TurnCompletion.kt`
- Modify: `app/src/main/java/com/aiopenpocket/agent/loop/TurnManager.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`

**Interfaces:**
- Consumes: `TurnLifeSupport.onProtectionLost` (Task 1), `ForegroundState.isInForeground()`.
- Produces: `TurnTexts.PROTECTION_EXPIRED` — текст ошибки, который увидит пользователь.

- [ ] **Step 1: Написать падающие тесты**

Дописать в `TurnManagerTest.kt`:

```kotlin
  @Test
  fun `истечение защиты в фоне валит ход с возможностью повторить`() = runTest {
    inForeground = false
    executor.script = { flow { awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    lifeSupport.loseProtection(setOf("c1"))
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Failed
    assertEquals(TurnTexts.PROTECTION_EXPIRED, status.message)
    assertTrue("реплика юзера в истории есть — «Повторить» обязано быть доступно", status.canRetry)
  }

  @Test
  fun `истечение защиты при открытом экране ход не трогает`() = runTest {
    inForeground = true
    executor.script = { flow { awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT)
    advanceUntilIdle()

    lifeSupport.loseProtection(setOf("c1"))
    advanceUntilIdle()

    assertTrue(
      "foreground-процесс система не вытесняет — резать длинный ход на глазах у юзера незачем",
      m.sessions.value.getValue("c1").status.value is TurnStatus.Running,
    )
  }
```

- [ ] **Step 2: Подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"
```

Expected: FAIL — `TurnTexts.PROTECTION_EXPIRED` не существует.

- [ ] **Step 3: Добавить текст ошибки**

В `object TurnTexts` (`TurnCompletion.kt`), после `TURN_DONE_FALLBACK`:

```kotlin
  /** Ход не уложился в окно фоновой защиты (shortService, ~3 мин) при свёрнутом приложении. */
  const val PROTECTION_EXPIRED =
    "Ход не уложился в три минуты фоновой работы и был остановлен системой. Нажми «Повторить»."
```

- [ ] **Step 4: Подписаться на потерю защиты в TurnManager**

Сразу после объявления `private val sessionsFlow` в `TurnManager` добавить:

```kotlin
  init {
    // Единственный подписчик: только менеджер владеет job-ами ходов и может их отменить.
    lifeSupport.onProtectionLost { protectedIds -> scope.launch { failProtectedTurns(protectedIds) } }
  }
```

И приватный метод рядом с `cancel`:

```kotlin
  /**
   * Система отобрала защиту (таймаут shortService). Гейт на видимости — ключевой: foreground-процесс
   * ОС не вытесняет, поэтому на экране защищать не от чего, и резать длинный GOAL-прогон там было бы
   * вредом без причины. В фоне же альтернатива «оставить ход без защиты» означает тихую пропажу
   * ответа, поэтому валим явно и с «Повторить».
   */
  private suspend fun failProtectedTurns(protectedIds: Set<String>) {
    if (foreground.isInForeground()) return
    mutex.withLock {
      protectedIds.forEach { convId ->
        val session = sessionsFlow.value[convId] ?: return@forEach
        if (session.status.value !is TurnStatus.Running) return@forEach
        session.job?.cancel()
        session.statusFlow.value =
          TurnStatus.Failed(TurnTexts.PROTECTION_EXPIRED, canRetry = session.lastUserText != null)
      }
    }
  }
```

- [ ] **Step 5: Подтвердить GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"
```

Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
git add app/src/main/java/com/aiopenpocket/agent/loop/TurnCompletion.kt app/src/main/java/com/aiopenpocket/agent/loop/TurnManager.kt app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt
git commit -m "feat(background-turns): потеря защиты валит ход только в фоне"
```

---

### Task 3: TurnForegroundService

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/notification/TurnForegroundService.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: `TurnForegroundService`, `TurnForegroundService.onSystemTimeout: (() -> Unit)?` — статический колбэк, который дёргается из `onTimeout`.

- [ ] **Step 1: Написать сервис**

```kotlin
package com.aiopenpocket.agent.core.notification

// @trace fonovye-hody-agenta-dde42d

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.aiopenpocket.MainActivity

/**
 * Держит процесс живым, пока идёт ход агента (спека `2026-07-28-turn-foreground-service-design.md`).
 * Поднимается и гасится только из [AndroidTurnLifeSupport] — снаружи стартовать его нечем и незачем.
 *
 * Тип `shortService`: система даёт около трёх минут и затем зовёт [onTimeout], после чего сервис
 * ОБЯЗАН остановиться, иначе приложение будет прибито. Поэтому [onTimeout] сначала сообщает наверх
 * (через [onSystemTimeout]), потом безусловно зовёт `stopSelf`.
 *
 * Связь через статический колбэк, а не через DI-граф: экземпляр сервиса создаёт система, и тянуть в
 * него `AppGraph` ради одного уведомления наверх было бы дороже, чем один `@Volatile`-указатель,
 * который выставляет singleton-владелец сервиса.
 */
class TurnForegroundService : Service() {

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // startForeground обязан случиться в первые ~5 секунд после startForegroundService, иначе
    // система прибьёт приложение — поэтому никакой отложенной инициализации здесь нет.
    startForeground(NOTIFICATION_ID, buildNotification())
    return START_NOT_STICKY
  }

  @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
  override fun onTimeout(startId: Int) = handleTimeout()

  @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
  override fun onTimeout(startId: Int, fgsType: Int) = handleTimeout()

  private fun handleTimeout() {
    onSystemTimeout?.invoke()
    stopSelf()
  }

  private fun buildNotification(): Notification {
    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      notificationManager.createNotificationChannel(
        // IMPORTANCE_LOW: без звука и вибрации. Отдельный канал от ai_agent_channel, чтобы юзер мог
        // приглушить «агент отвечает…», не глуша при этом напоминалки.
        NotificationChannel(CHANNEL_ID, "Ход агента", NotificationManager.IMPORTANCE_LOW),
      )
    }
    val intent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentIntent = PendingIntent.getActivity(
      this,
      NOTIFICATION_ID,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_dialog_info)
      .setContentTitle("Агент отвечает…")
      .setContentText("Ответ придёт, даже если свернуть приложение.")
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setContentIntent(contentIntent)
      // Намеренно НЕ FOREGROUND_SERVICE_IMMEDIATE: поведение по умолчанию позволяет системе
      // отложить показ примерно на 10 секунд, и типичный ход завершится раньше, чем уведомление
      // вообще появится. Это то, что делает цену фичи почти нулевой.
      .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFAULT)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "turn_foreground_channel"
    private const val NOTIFICATION_ID = 4242

    /** Выставляется [AndroidTurnLifeSupport] при сборке графа; дёргается из [onTimeout]. */
    @Volatile
    var onSystemTimeout: (() -> Unit)? = null
  }
}
```

- [ ] **Step 2: Объявить разрешения и сервис в манифесте**

В `AndroidManifest.xml` после `<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />`:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SHORT_SERVICE" />
```

Внутри `<application>`, рядом с прочими компонентами:

```xml
        <!-- Живёт только пока идёт ход агента; поднимается из AndroidTurnLifeSupport. -->
        <service
            android:name="com.aiopenpocket.agent.core.notification.TurnForegroundService"
            android:exported="false"
            android:foregroundServiceType="shortService" />
```

- [ ] **Step 3: Проверить сборку**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/java/com/aiopenpocket/agent/core/notification/TurnForegroundService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(background-turns): foreground-сервис shortService на время хода"
```

---

### Task 4: AndroidTurnLifeSupport — рефкаунт и старт/стоп сервиса

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/notification/AndroidTurnLifeSupport.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/notification/AndroidTurnLifeSupportTest.kt`

**Interfaces:**
- Consumes: `TurnLifeSupport` (Task 1), `TurnForegroundService` (Task 3).
- Produces: биндинг `TurnLifeSupport` в `AppScope`.

- [ ] **Step 1: Написать падающий тест**

```kotlin
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
 * Рефкаунт проверяем через реальный Robolectric-Application: сервис поднимается один раз на первом
 * ходе и гаснет на последнем. Настоящее поведение FGS (выживание процесса, системный onTimeout,
 * отложенный показ уведомления) Robolectric не воспроизводит — это на устройство.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidTurnLifeSupportTest {

  private val application = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun `первый acquire поднимает сервис, второй — нет`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)
    val shadow = shadowOf(application)

    assertTrue(lifeSupport.acquire("c1"))
    assertEquals(
      TurnForegroundService::class.java.name,
      shadow.nextStartedService.component?.className,
    )
    assertNull("второй ход едет на уже поднятом сервисе", shadow.nextStartedService)

    assertTrue(lifeSupport.acquire("c2"))
    assertNull(shadow.nextStartedService)
  }

  @Test
  fun `сервис гаснет только на последнем release`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)
    val shadow = shadowOf(application)
    lifeSupport.acquire("c1")
    lifeSupport.acquire("c2")
    shadow.nextStartedService // снимаем запись о старте

    lifeSupport.release("c1")
    assertNull("остался ещё один защищённый ход", shadow.nextStoppedService)

    lifeSupport.release("c2")
    assertEquals(
      TurnForegroundService::class.java.name,
      shadow.nextStoppedService?.component?.className,
    )
  }

  @Test
  fun `повторный acquire того же диалога считается, а не схлопывается`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)
    val shadow = shadowOf(application)
    lifeSupport.acquire("c1")
    lifeSupport.acquire("c1") // новый ход того же чата поверх отменяемого старого
    shadow.nextStartedService

    lifeSupport.release("c1")
    assertNull("старый ход отпустил защиту — новый её ещё держит", shadow.nextStoppedService)

    lifeSupport.release("c1")
    assertEquals(
      TurnForegroundService::class.java.name,
      shadow.nextStoppedService?.component?.className,
    )
  }

  @Test
  fun `потеря защиты отдаёт защищённые диалоги и обнуляет счётчики`() = runTest {
    val lifeSupport = AndroidTurnLifeSupport(application)
    var reported: Set<String>? = null
    lifeSupport.onProtectionLost { reported = it }
    lifeSupport.acquire("c1")
    lifeSupport.acquire("c2")

    TurnForegroundService.onSystemTimeout?.invoke()

    assertEquals(setOf("c1", "c2"), reported)
    // Счётчики обнулены: сервис система уже забрала, и следующий ход обязан поднять его заново.
    shadowOf(application).nextStartedService
    lifeSupport.acquire("c3")
    assertEquals(
      TurnForegroundService::class.java.name,
      shadowOf(application).nextStartedService?.component?.className,
    )
  }
}
```

- [ ] **Step 2: Подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.notification.AndroidTurnLifeSupportTest"
```

Expected: FAIL — `AndroidTurnLifeSupport` не существует.

- [ ] **Step 3: Реализовать**

```kotlin
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
 * дойдёт до своего `release`, поэтому по одному conversationId вполне бывает два одновременных
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
    // ошибка программиста и не повод валить ход — просто гарантии не будет, см. спеку раздел 6.
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
   * Счётчики обнуляются здесь, а не в `release`: сервис к этому моменту система уже забрала, и без
   * обнуления следующий `acquire` увидел бы непустую мапу, решил «сервис уже поднят» и вернул бы
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
```

- [ ] **Step 4: Подтвердить GREEN**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.notification.AndroidTurnLifeSupportTest"
```

Expected: PASS, четыре теста.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/java/com/aiopenpocket/agent/core/notification/AndroidTurnLifeSupport.kt app/src/test/java/com/aiopenpocket/agent/core/notification/AndroidTurnLifeSupportTest.kt
git commit -m "feat(background-turns): рефкаунт защиты и владение сервисом"
```

---

### Task 5: Прокидка в граф и финальная верификация

**Files:**
- Modify: `app/src/main/java/com/aiopenpocket/di/TurnModule.kt`
- Modify: `traceability.yaml`

**Interfaces:**
- Consumes: всё из Tasks 1–4.

- [ ] **Step 1: Прокинуть порт в TurnManager**

В `TurnModule.provideTurnManager` добавить параметр после `notifier: TurnNotifier,`:

```kotlin
    lifeSupport: com.aiopenpocket.agent.loop.TurnLifeSupport,
```

и в конструкторе `TurnManager(...)` после `notifier = notifier,`:

```kotlin
    lifeSupport = lifeSupport,
```

- [ ] **Step 2: Обновить rationale фичи**

В `traceability.yaml` у `fonovye-hody-agenta-dde42d` дополнить `rationale`, добавив в конец:

```text
С 2026-07-28 ход, начатый с экрана, дополнительно защищён foreground-сервисом (shortService) на время исполнения — свёрнутое приложение больше не теряет ответ.
```

- [ ] **Step 3: Полный прогон**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
traxer check
```

Expected: BUILD SUCCESSFUL, 0 errors у traxer.

- [ ] **Step 4: Коммит**

```bash
git add app/src/main/java/com/aiopenpocket/di/TurnModule.kt traceability.yaml
git commit -m "feat(background-turns): собрать защиту хода в графе"
```

- [ ] **Step 5: Ручная проверка на устройстве (часть Definition of Done)**

Robolectric ничего из этого не проверяет — только руками:

1. Отправить сообщение → сразу свернуть приложение → дождаться → ответ в истории, пуш «агент ответил».
2. Короткий ход при открытом экране → уведомления «Агент отвечает…» быть НЕ должно (система откладывает показ ~10 с).
3. Отправить → свернуть → смахнуть из recents → ответ всё равно дописан.
4. Длинный GOAL при открытом экране → идёт дольше трёх минут и НЕ падает.
5. Длинный GOAL в фоне → на ~3 минуте падает с текстом про фоновую защиту и рабочим «Повторить».

## Definition of Done

- Все пять задач закрыты, тесты зелёные, `assembleDebug` собирается, `traxer check` без ошибок.
- Ручной чеклист Step 5 Task 5 пройден на реальном устройстве.
- Отказ в захвате защиты не меняет наблюдаемое поведение по сравнению с сегодняшним.
