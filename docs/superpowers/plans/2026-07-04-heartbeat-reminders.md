# Heartbeat/напоминалки + урок — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Тул `heartbeat` (напоминалки-пуши и фоновые пробуждения агента через WorkManager), живая карточка `reminder` в чате, урок `heartbeat_and_cron`.

**Architecture:** Room-таблица `heartbeats` — источник правды; `HeartbeatTool` пишет туда и ставит `HeartbeatWorker` (CoroutineWorker), воркер в agent-режиме гоняет существующий `ReactLoopRunner` headless с безопасным подмножеством тулз и пишет результат в исходный диалог + пуш. Карточка читает статус из Room по Flow. Спека: `docs/superpowers/specs/2026-07-04-heartbeat-reminders-design.md`.

**Tech Stack:** Kotlin, Room (v1 baseline + `fallbackToDestructiveMigration`), WorkManager 2.10.0, Metro DI, Compose, JUnit4 + Robolectric + kotlinx-coroutines-test.

**Отступления от спеки (согласованы здравым смыслом, отметить в ревью):**
1. Вместо кастомной `WorkerFactory`/`Configuration.Provider` воркер берёт зависимости через `(applicationContext as AiOpenPocketApp).appGraph` — меньше кода, дефолтная фабрика продолжает работать для `NotificationWorker`.
2. Добавлен deep-link «тап по пушу → открыть диалог» через intent-extra в MainActivity (в спеке заявлен, но механизм не был расписан).

## Global Constraints

- Package `com.aiopenpocket`, но исходники лежат под `app/src/main/java/com/example/...` — класть новые файлы рядом с соседями по пакету, не «чинить» раскладку.
- Room: версия остаётся **1** (pre-release baseline, `fallbackToDestructiveMigration()` в `DatabaseBindings.kt:30` — база пересоздастся). Миграции НЕ писать.
- Тесты: JUnit4; Robolectric с `@Config(sdk = [36])`; корутины — `runTest`. Запуск: `./gradlew testDebugUnitTest`.
- Кол-во тулз после этого плана: 14 (сейчас 13). Имена существующих тулз: `file_tool, memory_tool, render_card, notifications, external_files, database_query, work_manager, calendar, alarm, email, sms, step_counter, srs_tool` (+ `load_skill`).
- Traceability (CLAUDE.md): в Task 1 сгенерировать id (`traxer id`), завести запись в `traceability.yaml`; в каждом новом файле маркер `// @trace <id>`; каждый коммит — с трейлером `Trace: <id>`. Урок дополнительно маркируется существующим id уроков `uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2` (см. Task 8).
- Все коммиты: `git commit` с трейлерами `Trace: <id>` и `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Перед финальным завершением: `traxer check` → 0 errors.

---

### Task 1: HeartbeatEntity + HeartbeatDao + регистрация в БД

**Files:**
- Create: `app/src/main/java/com/example/agent/core/memory/entity/HeartbeatEntity.kt`
- Create: `app/src/main/java/com/example/agent/core/memory/dao/HeartbeatDao.kt`
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt` (entities-список + abstract dao)
- Modify: `app/src/main/java/com/example/di/DatabaseBindings.kt` (provide DAO)
- Modify: `traceability.yaml` (новая запись)
- Test: `app/src/test/java/com/aiopenpocket/agent/core/memory/dao/HeartbeatDaoTest.kt`

**Interfaces:**
- Consumes: `AppDatabase`, паттерн `SrsWordDaoTest` (in-memory Room + Robolectric).
- Produces: `HeartbeatEntity(id, title, mode, prompt?, message?, agentId?, conversationId, kind, fireAt?, intervalMinutes?, status, createdAt, lastRunAt?, lastResult?)`; `HeartbeatDao.insert/getById/observe/listActive/setStatus/recordRun`. Статусы-строки: `"scheduled" | "fired" | "cancelled" | "error"`; mode: `"push" | "agent"`; kind: `"one_time" | "periodic"`.

- [ ] **Step 1: Сгенерировать trace id и завести запись**

Run: `traxer id` — получить `<TRACE_ID>` (далее во всех задачах он подставляется вместо `<TRACE_ID>`).

В `traceability.yaml` добавить запись по образцу соседних:

```yaml
- id: <TRACE_ID>
  title: Heartbeat/напоминалки агента
  rationale: >
    Дать агенту проактивность: отложенные пуши-напоминалки и периодические фоновые
    пробуждения (heartbeat), где агент сам решает, беспокоить ли юзера (паттерн
    HEARTBEAT_OK). Через WorkManager, реестр в Room, живая карточка в чате.
```

- [ ] **Step 2: Написать падающий DAO-тест**

`app/src/test/java/com/aiopenpocket/agent/core/memory/dao/HeartbeatDaoTest.kt`:

```kotlin
package com.aiopenpocket.agent.core.memory.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeartbeatDaoTest {

  private lateinit var db: AppDatabase
  private lateinit var dao: HeartbeatDao

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    dao = db.heartbeatDao()
  }

  @After
  fun tearDown() = db.close()

  private fun hb(id: String = "hb1", status: String = "scheduled") = HeartbeatEntity(
    id = id,
    title = "Проверка бюджета",
    mode = "agent",
    prompt = "проверь бюджет",
    conversationId = "conv1",
    kind = "periodic",
    intervalMinutes = 30,
    status = status,
  )

  @Test
  fun `insert and getById round-trip`() = runTest {
    dao.insert(hb())
    val loaded = dao.getById("hb1")
    assertEquals("Проверка бюджета", loaded?.title)
    assertEquals("agent", loaded?.mode)
    assertEquals(30L, loaded?.intervalMinutes)
  }

  @Test
  fun `observe emits updates`() = runTest {
    dao.insert(hb())
    dao.setStatus("hb1", "cancelled")
    assertEquals("cancelled", dao.observe("hb1").first()?.status)
  }

  @Test
  fun `listActive returns only scheduled`() = runTest {
    dao.insert(hb(id = "a"))
    dao.insert(hb(id = "b", status = "cancelled"))
    assertEquals(listOf("a"), dao.listActive().map { it.id })
  }

  @Test
  fun `recordRun updates status lastRunAt lastResult`() = runTest {
    dao.insert(hb())
    dao.recordRun("hb1", status = "fired", lastRunAt = 42L, lastResult = "ok")
    val loaded = dao.getById("hb1")
    assertEquals("fired", loaded?.status)
    assertEquals(42L, loaded?.lastRunAt)
    assertEquals("ok", loaded?.lastResult)
  }

  @Test
  fun `getById returns null for unknown id`() = runTest {
    assertNull(dao.getById("nope"))
  }
}
```

- [ ] **Step 3: Прогнать тест — убедиться, что падает**

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.core.memory.dao.HeartbeatDaoTest"`
Expected: FAIL — `Unresolved reference: HeartbeatEntity` / `heartbeatDao`.

- [ ] **Step 4: Создать Entity и DAO**

`app/src/main/java/com/example/agent/core/memory/entity/HeartbeatEntity.kt`:

```kotlin
package com.aiopenpocket.agent.core.memory.entity

// @trace <TRACE_ID>

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Реестр напоминалок/heartbeat'ов — источник правды для карточки в чате и для
 * [com.aiopenpocket.agent.workers.HeartbeatWorker]. В WorkManager уходит только id
 * (обходим 10KB-лимит input Data); unique work name = "heartbeat_<id>".
 */
@Entity(tableName = "heartbeats")
data class HeartbeatEntity(
  @PrimaryKey val id: String,
  val title: String,
  /** "push" — статичный пуш без LLM; "agent" — фоновое пробуждение агента. */
  val mode: String,
  /** Задача для agent-режима; null = дефолтный «пульс» (агент сам решает, писать ли). */
  val prompt: String? = null,
  /** Текст пуша для push-режима. */
  val message: String? = null,
  /** Мини-агент, от чьего имени просыпаемся; null = дефолтный ассистент. */
  val agentId: String? = null,
  /** Диалог, куда пишется результат agent-режима (и который открывает тап по пушу). */
  val conversationId: String,
  /** "one_time" | "periodic". */
  val kind: String,
  /** Epoch millis планового срабатывания (one_time). */
  val fireAt: Long? = null,
  val intervalMinutes: Long? = null,
  /** "scheduled" | "fired" | "cancelled" | "error". Periodic после срабатывания остаётся scheduled. */
  val status: String = "scheduled",
  val createdAt: Long = System.currentTimeMillis(),
  val lastRunAt: Long? = null,
  /** Короткая выжимка последнего результата — для карточки. */
  val lastResult: String? = null,
)
```

`app/src/main/java/com/example/agent/core/memory/dao/HeartbeatDao.kt`:

```kotlin
package com.aiopenpocket.agent.core.memory.dao

// @trace <TRACE_ID>

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HeartbeatDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(heartbeat: HeartbeatEntity)

  @Query("SELECT * FROM heartbeats WHERE id = :id")
  suspend fun getById(id: String): HeartbeatEntity?

  /** Живая карточка в чате подписывается на эту строку. */
  @Query("SELECT * FROM heartbeats WHERE id = :id")
  fun observe(id: String): Flow<HeartbeatEntity?>

  @Query("SELECT * FROM heartbeats WHERE status = 'scheduled' ORDER BY createdAt DESC")
  suspend fun listActive(): List<HeartbeatEntity>

  @Query("UPDATE heartbeats SET status = :status WHERE id = :id")
  suspend fun setStatus(id: String, status: String)

  @Query("UPDATE heartbeats SET status = :status, lastRunAt = :lastRunAt, lastResult = :lastResult WHERE id = :id")
  suspend fun recordRun(id: String, status: String, lastRunAt: Long, lastResult: String?)
}
```

В `AppDatabase.kt`: добавить `HeartbeatEntity::class` в `entities` (версия остаётся 1) и `abstract fun heartbeatDao(): HeartbeatDao`.

В `DatabaseBindings.kt` рядом с остальными DAO-провайдерами:

```kotlin
@Provides
fun provideHeartbeatDao(database: AppDatabase): HeartbeatDao = database.heartbeatDao()
```

- [ ] **Step 5: Прогнать тест — зелёный**

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.core.memory.dao.HeartbeatDaoTest"`
Expected: PASS (5 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/agent/core/memory/ app/src/main/java/com/example/data/database/AppDatabase.kt app/src/main/java/com/example/di/DatabaseBindings.kt app/src/test/java/com/aiopenpocket/agent/core/memory/dao/HeartbeatDaoTest.kt traceability.yaml
git commit -m "feat(heartbeat): Room-реестр heartbeats + DAO

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: TurnContextHolder — тулу нужен conversationId/agentId текущего хода

Тулзы выполняются без контекста диалога (`ToolExecutor.execute(name, args)`), а `heartbeat` должен знать, в какой диалог писать результат и от чьего имени. **Важно (изменение относительно исходного плана):** появившийся `TurnManager` гоняет ходы РАЗНЫХ диалогов параллельно, поэтому глобальный volatile-холдер отдал бы тулу чужой `conversationId`. Решение — thread-local, привязанный к корутине хода через `ThreadLocal.asContextElement`: тул-вызовы внутри хода читают именно свой контекст. Тул-facing API (`conversationId`/`agentId`/`set`) сохраняется, concurrency-safety спрятана внутрь.

**Files:**
- Create: `app/src/main/java/com/example/agent/loop/TurnContextHolder.kt`
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt` (инжект + `withContext(contextElement)` вокруг `reactLoopRunner.run`, строка ~78)
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnContextHolderTest.kt` (тест-дерево под `com/aiopenpocket/...`, как `AgentModeTest`)

**Interfaces:**
- Produces: `TurnContextHolder.contextElement(conversationId, agentId): CoroutineContext` (обёртка хода), `set(conversationId, agentId)` (тест/синхронный seam), `val conversationId: String?`, `val agentId: String?` (читает тул). Инжектится через Metro `@Inject` + `@SingleIn(AppScope::class)`.

- [ ] **Step 1: Написать падающий тест**

`app/src/test/java/com/aiopenpocket/agent/loop/TurnContextHolderTest.kt`:

```kotlin
package com.aiopenpocket.agent.loop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnContextHolderTest {

  @Test
  fun `empty by default`() {
    val holder = TurnContextHolder()
    assertNull(holder.conversationId)
    assertNull(holder.agentId)
  }

  @Test
  fun `set seeds current thread`() {
    val holder = TurnContextHolder()
    holder.set("conv1", "reminders")
    assertEquals("conv1", holder.conversationId)
    assertEquals("reminders", holder.agentId)
  }

  // Гарантия против гонки: TurnManager гоняет ходы разных диалогов параллельно. Каждый ход в своей
  // обёртке contextElement должен видеть СВОЙ conversationId, даже перескочив на другой поток.
  @Test
  fun `contextElement isolates concurrent turns`() = runTest {
    val holder = TurnContextHolder()
    val results = listOf("A" to "budget", "B" to "diary").map { (conv, agent) ->
      async(Dispatchers.Default) {
        withContext(holder.contextElement(conv, agent)) {
          yield() // спровоцировать возможный перескок потока внутри хода
          holder.conversationId to holder.agentId
        }
      }
    }.awaitAll()
    assertEquals(listOf("A" to "budget", "B" to "diary"), results)
    assertNull(holder.conversationId) // вне обёртки контекст снова пуст
  }
}
```

- [ ] **Step 2: Прогнать — FAIL** (`Unresolved reference: TurnContextHolder`)

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnContextHolderTest"`

- [ ] **Step 3: Реализация**

`app/src/main/java/com/example/agent/loop/TurnContextHolder.kt`:

```kotlin
package com.aiopenpocket.agent.loop

// @trace heartbeat-napominalki-agenta-5f0442

import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Контекст текущего хода агента (диалог + мини-агент) для тулз, которым он нужен (heartbeat пишет
 * результат в «свой» диалог). Хранится thread-local и прокидывается через coroutine-context:
 * [com.aiopenpocket.agent.loop.TurnManager] гоняет ходы РАЗНЫХ диалогов параллельно, поэтому
 * глобальный volatile отдал бы тулу чужой conversationId. [contextElement] привязывает значение к
 * корутине хода — тул-вызовы внутри неё читают именно свой контекст, на каком бы потоке ни возобновились.
 */
@SingleIn(AppScope::class)
class TurnContextHolder @Inject constructor() {
  data class Ctx(val conversationId: String?, val agentId: String?)

  private val threadLocal = ThreadLocal<Ctx?>()

  val conversationId: String? get() = threadLocal.get()?.conversationId
  val agentId: String? get() = threadLocal.get()?.agentId

  /** Обернуть ход: `withContext(holder.contextElement(convId, agentId)) { reactLoopRunner.run(...) }`. */
  fun contextElement(conversationId: String?, agentId: String?): CoroutineContext =
    threadLocal.asContextElement(Ctx(conversationId, agentId))

  /** Прямая установка на текущем потоке — для тестов и синхронных путей. */
  fun set(conversationId: String?, agentId: String?) {
    threadLocal.set(Ctx(conversationId, agentId))
  }
}
```

В `AgentLoopOrchestrator`: добавить в конструктор `private val turnContextHolder: TurnContextHolder,`,
импорт `kotlinx.coroutines.withContext`, и обернуть вызов `reactLoopRunner.run(...)` (строка ~78) в
`withContext` с контекстом хода — тогда `heartbeat`, вызванный внутри хода, прочитает верный диалог/агента:

```kotlin
val baseResult = withContext(turnContextHolder.contextElement(conversationId, context.agentId)) {
  reactLoopRunner.run(
    conversationId, messages, tools, provider, turn.maxIterations, context.agentId,
  ) { send(it) }
}
```

- [ ] **Step 4: Прогнать — PASS**, плюс общий build не сломан: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.loop.*"`

- [ ] **Step 5: Commit**

```bash
# ВНИМАНИЕ: НЕ `git add .../agent/loop/` целиком — в этой папке живут файлы другой фичи. Только свои:
git add app/src/main/java/com/example/agent/loop/TurnContextHolder.kt app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt app/src/test/java/com/aiopenpocket/agent/loop/TurnContextHolderTest.kt
git commit -m "feat(heartbeat): TurnContextHolder — контекст хода для тулз

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: HeartbeatTool (schedule / cancel / list)

**Files:**
- Create: `app/src/main/java/com/example/agent/tools/HeartbeatTool.kt`
- Modify: `gradle/libs.versions.toml` (+ work-testing), `app/build.gradle.kts` (+ testImplementation)
- Test: `app/src/test/java/com/aiopenpocket/agent/tools/HeartbeatToolTest.kt`

**Interfaces:**
- Consumes: `HeartbeatDao` (Task 1), `TurnContextHolder` (Task 2), `toolSchema`/`string`/`long`/`missingArg` из `ToolSchemaBuilder.kt`, `HeartbeatWorker.KEY_HEARTBEAT_ID` (появится в Task 4 — в этом таске объявить константу в самом туле и переиспользовать позже, см. Step 3).
- Produces: тул `name = "heartbeat"`. `schedule` возвращает `ToolResult.Success`, содержащий `heartbeat_id` и инструкцию показать карточку. Unique work name: `"heartbeat_<id>"`.

- [ ] **Step 1: Добавить work-testing**

`gradle/libs.versions.toml` (секция libraries, рядом с `androidx-work-runtime`):

```toml
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "workManager" }
```

`app/build.gradle.kts` (рядом с остальными testImplementation):

```kotlin
testImplementation(libs.androidx.work.testing)
```

- [ ] **Step 2: Написать падающий тест**

`app/src/test/java/com/aiopenpocket/agent/tools/HeartbeatToolTest.kt`:

```kotlin
package com.aiopenpocket.agent.tools

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeartbeatToolTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var holder: TurnContextHolder
  private lateinit var tool: HeartbeatTool

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    // POST_NOTIFICATIONS: Robolectric по умолчанию НЕ выдаёт runtime-разрешение — выдаём явно.
    shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    WorkManagerTestInitHelper.initializeTestWorkManager(context)
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    holder = TurnContextHolder().apply { set("conv1", "reminders") }
    tool = HeartbeatTool(context, db.heartbeatDao(), holder)
  }

  @After
  fun tearDown() = db.close()

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  @Test
  fun `schedule push one-time writes row and returns id`() = runTest {
    val result = tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "Позвонить маме",
        "message" to "Позвони!", "delay_minutes" to "30"),
    )
    assertTrue(result is ToolResult.Success)
    val row = db.heartbeatDao().listActive().single()
    assertEquals("push", row.mode)
    assertEquals("one_time", row.kind)
    assertEquals("conv1", row.conversationId)
    assertTrue((result as ToolResult.Success).data.contains(row.id))
    assertTrue(result.data.contains("render_card"))
  }

  @Test
  fun `schedule agent periodic stores prompt and agentId`() = runTest {
    tool.execute(
      args("action" to "schedule", "mode" to "agent", "title" to "Пульс задач",
        "prompt" to "проверь задачи", "interval_minutes" to "30"),
    )
    val row = db.heartbeatDao().listActive().single()
    assertEquals("agent", row.mode)
    assertEquals("periodic", row.kind)
    assertEquals("проверь задачи", row.prompt)
    assertEquals("reminders", row.agentId)
    assertEquals(30L, row.intervalMinutes)
  }

  @Test
  fun `periodic interval below 15 is rejected`() = runTest {
    val result = tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "x",
        "message" to "y", "interval_minutes" to "5"),
    )
    assertTrue(result is ToolResult.Error)
  }

  @Test
  fun `schedule without delay and interval is rejected`() = runTest {
    val result = tool.execute(args("action" to "schedule", "mode" to "push", "title" to "x"))
    assertTrue(result is ToolResult.Error)
  }

  @Test
  fun `cancel sets status and returns success`() = runTest {
    tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "x",
        "message" to "y", "delay_minutes" to "10"),
    )
    val id = db.heartbeatDao().listActive().single().id
    val result = tool.execute(args("action" to "cancel", "heartbeat_id" to id))
    assertTrue(result is ToolResult.Success)
    assertEquals("cancelled", db.heartbeatDao().getById(id)?.status)
  }

  @Test
  fun `list returns active heartbeats`() = runTest {
    tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "Уникальный заголовок",
        "message" to "y", "delay_minutes" to "10"),
    )
    val result = tool.execute(args("action" to "list"))
    assertTrue((result as ToolResult.Success).data.contains("Уникальный заголовок"))
  }

  @Test
  fun `schedule without notification permission returns recoverable error`() = runTest {
    shadowOf(context as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    val result = tool.execute(
      args("action" to "schedule", "mode" to "push", "title" to "x",
        "message" to "y", "delay_minutes" to "10"),
    )
    assertTrue(result is ToolResult.Error)
    assertTrue((result as ToolResult.Error).message.contains("notification_permission"))
  }
}
```

- [ ] **Step 3: Прогнать — FAIL**, затем реализация

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.tools.HeartbeatToolTest"` → `Unresolved reference: HeartbeatTool`.

`app/src/main/java/com/example/agent/tools/HeartbeatTool.kt`:

```kotlin
package com.aiopenpocket.agent.tools

// @trace <TRACE_ID>

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.long
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.agent.workers.HeartbeatWorker
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Напоминалки и heartbeat агента. mode=push — статичный пуш в назначенное время;
 * mode=agent — фоновое пробуждение агента (с задачей из prompt или дефолтным «пульсом»,
 * где агент сам решает, писать ли юзеру — HEARTBEAT_OK = молчать). Источник правды — Room
 * (heartbeats), WorkManager получает только id.
 */
@Inject
@SingleIn(AppScope::class)
class HeartbeatTool(
  private val context: Context,
  private val heartbeatDao: HeartbeatDao,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  override val name = "heartbeat"
  override val description =
    "Напоминалки и heartbeat: отложенный/периодический пуш (mode=push) или фоновое " +
    "пробуждение агента (mode=agent) через WorkManager. После успешного schedule СРАЗУ вызови " +
    "render_card с template=\"reminder\" и params={\"heartbeat_id\":\"<id из ответа>\"} — " +
    "юзер увидит живую карточку с кнопкой отмены."
  override val schema = toolSchema(name, description) {
    string(
      "action", "Действие", required = true,
      enum = listOf("schedule", "cancel", "list"),
    )
    string("mode", "push — просто уведомление; agent — разбудить агента", enum = listOf("push", "agent"))
    string("title", "Короткий заголовок (обязателен для schedule)")
    string("message", "Текст пуша (mode=push)")
    string(
      "prompt",
      "Задача для агента (mode=agent). Не задан — «пульс»: агент сам решит, есть ли что сообщить.",
    )
    integer("delay_minutes", "Одноразово: через сколько минут сработать")
    integer("interval_minutes", "Периодически: интервал в минутах (минимум 15 — лимит WorkManager)")
    string("heartbeat_id", "ID для cancel")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    return when (arguments.string("action") ?: return missingArg("action")) {
      "schedule" -> schedule(arguments)
      "cancel" -> cancel(arguments)
      "list" -> list()
      else -> ToolResult.Error("Unknown action")
    }
  }

  private suspend fun schedule(args: Map<String, JsonElement>): ToolResult {
    if (!hasNotificationPermission()) {
      return ToolResult.Error(
        "Нет разрешения на уведомления. Покажи карточку render_card " +
          "template=\"notification_permission\" и повтори schedule после выдачи разрешения.",
      )
    }
    val title = args.string("title") ?: return missingArg("title")
    val mode = args.string("mode") ?: "push"
    val delay = args.long("delay_minutes")
    val interval = args.long("interval_minutes")
    if (delay == null && interval == null) {
      return ToolResult.Error("Нужен delay_minutes (одноразово) или interval_minutes (периодически).")
    }
    if (interval != null && interval < 15) {
      return ToolResult.Error("Минимальный интервал периодической задачи — 15 минут (лимит WorkManager).")
    }
    val conversationId = turnContextHolder.conversationId
      ?: return ToolResult.Error("Нет активного диалога для heartbeat.", recoverable = false)

    val id = UUID.randomUUID().toString()
    val entity = HeartbeatEntity(
      id = id,
      title = title,
      mode = mode,
      prompt = args.string("prompt"),
      message = args.string("message"),
      agentId = turnContextHolder.agentId,
      conversationId = conversationId,
      kind = if (interval != null) "periodic" else "one_time",
      fireAt = delay?.let { System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(it) },
      intervalMinutes = interval,
    )
    heartbeatDao.insert(entity)

    val input = Data.Builder().putString(HeartbeatWorker.KEY_HEARTBEAT_ID, id).build()
    val workManager = WorkManager.getInstance(context)
    val workName = workName(id)
    if (interval != null) {
      val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(interval, TimeUnit.MINUTES)
        .setInputData(input)
        .build()
      workManager.enqueueUniquePeriodicWork(workName, ExistingPeriodicWorkPolicy.UPDATE, request)
    } else {
      val request = OneTimeWorkRequestBuilder<HeartbeatWorker>()
        .setInitialDelay(delay ?: 0L, TimeUnit.MINUTES)
        .setInputData(input)
        .build()
      workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, request)
    }

    val schedule = if (interval != null) "каждые $interval мин" else "через $delay мин"
    return ToolResult.Success(
      "Запланировано ($schedule). heartbeat_id=$id. " +
        "Теперь вызови render_card template=\"reminder\" params={\"heartbeat_id\":\"$id\"}.",
    )
  }

  private suspend fun cancel(args: Map<String, JsonElement>): ToolResult {
    val id = args.string("heartbeat_id") ?: return missingArg("heartbeat_id")
    WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    heartbeatDao.setStatus(id, "cancelled")
    return ToolResult.Success("Отменено: $id")
  }

  private suspend fun list(): ToolResult {
    val active = heartbeatDao.listActive()
    if (active.isEmpty()) return ToolResult.Success("Активных напоминаний нет.")
    val lines = active.joinToString("\n") { hb ->
      val schedule = hb.intervalMinutes?.let { "каждые $it мин" } ?: "одноразово"
      "- ${hb.title} [${hb.mode}, $schedule] id=${hb.id}"
    }
    return ToolResult.Success(lines)
  }

  private fun hasNotificationPermission(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
      PackageManager.PERMISSION_GRANTED

  companion object {
    fun workName(heartbeatId: String) = "heartbeat_$heartbeatId"
  }
}
```

Компиляция потребует `HeartbeatWorker.KEY_HEARTBEAT_ID` — создать заглушку воркера уже сейчас (полная логика — Task 5):

`app/src/main/java/com/example/agent/workers/HeartbeatWorker.kt`:

```kotlin
package com.aiopenpocket.agent.workers

// @trace <TRACE_ID>

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HeartbeatWorker(
  context: Context,
  params: WorkerParameters,
) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result = Result.success() // Логика — в Task 5.

  companion object {
    const val KEY_HEARTBEAT_ID = "heartbeat_id"
  }
}
```

- [ ] **Step 4: Прогнать — PASS** (7 tests)

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.tools.HeartbeatToolTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/agent/tools/HeartbeatTool.kt app/src/main/java/com/example/agent/workers/HeartbeatWorker.kt app/src/test/java/com/aiopenpocket/agent/tools/HeartbeatToolTest.kt gradle/libs.versions.toml app/build.gradle.kts
git commit -m "feat(heartbeat): тул heartbeat — schedule/cancel/list

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: HeartbeatRunner — фоновый ход агента

Вся логика срабатывания — в отдельном классе (чисто тестируется с фейками), воркер останется тонкой обёрткой.

**Files:**
- Create: `app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/workers/HeartbeatRunnerTest.kt` (тест-дерево под `com/aiopenpocket/...`)

**Interfaces:**
- Consumes: `HeartbeatDao`, `ReactLoopRunner` (конкретный класс — в тестах собирается с fake `LlmClient`; `LlmClient` — интерфейс `fun streamChat(messages, tools, provider): Flow<StreamEvent>`), `ToolRegistry`, `ConversationRepository.addMessage(conversationId, role, content, toolCallsJson=null, cardSpec=null): String`, `TurnNotifier.turnFinished(conversationId, conversationTitle, preview, failed)` — **порт из turns-фичи** (`agent/loop/TurnPorts.kt`), пуш с deep-link в диалог; реализация `AgentTurnNotifier`. **Deep-link уже реализован turns-фичей — свои Intent-константы НЕ вводим.** `MiniAgentCatalog.get(id: String?): MiniAgent?` (готовый lookup в `MiniAgent.kt`).
- Produces: `suspend fun handleFire(heartbeatId: String, attempt: Int): FireOutcome`; `enum class FireOutcome { DONE, RETRY }`; `SAFE_TOOL_NAMES = setOf("memory_tool", "database_query", "calendar", "step_counter", "notifications")`; `BG_MAX_ITERATIONS = 5`; `MAX_ATTEMPTS = 3`.

- [ ] **Step 1: Написать падающие тесты**

`app/src/test/java/com/aiopenpocket/agent/workers/HeartbeatRunnerTest.kt`. Fake LlmClient скриптуется списком событий; ReactLoopRunner собирается настоящий (fake `ToolExecutor` не нужен — сценарии без tool-call'ов). `TurnNotifier` — fake, копящий вызовы (ассертим пуш). Зависимости `ToolExecutor`/`MemoryRepository`/`ConversationRepository` — по образцу существующих фейков в `ToolExecutorTest`/`PrePromptAssembler`-тестах; если конструктор требует Room — собрать in-memory `AppDatabase` как в `HeartbeatDaoTest`.

```kotlin
package com.aiopenpocket.agent.workers

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeartbeatRunnerTest {

  private class FakeLlm(private val events: List<StreamEvent>) : LlmClient {
    override fun streamChat(
      messages: List<ChatMessage>,
      tools: List<FunctionSchema>,
      provider: LlmProvider,
    ): Flow<StreamEvent> = flowOf(*events.toTypedArray())
  }

  private class FakeNotifier : TurnNotifier {
    data class Call(val conversationId: String, val title: String?, val preview: String, val failed: Boolean)
    val calls = mutableListOf<Call>()
    override fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean) {
      calls += Call(conversationId, conversationTitle, preview, failed)
    }
  }

  private lateinit var context: Context
  private lateinit var db: AppDatabase
  private lateinit var notifier: FakeNotifier

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    notifier = FakeNotifier()
  }

  @After
  fun tearDown() = db.close()

  // runner(events) — хелпер: собрать HeartbeatRunner c FakeLlm поверх настоящего ReactLoopRunner,
  // DAO из db, настоящим ConversationRepository и [notifier]. Конструкторы ReactLoopRunner/
  // ConversationRepository сверить по коду; TurnNotifier — передать this@…notifier.

  private fun hb(mode: String, prompt: String? = null, kind: String = "one_time") = HeartbeatEntity(
    id = "hb1", title = "T", mode = mode, prompt = prompt, message = "msg",
    conversationId = "conv1", kind = kind, intervalMinutes = if (kind == "periodic") 30 else null,
  )

  @Test
  fun `unknown or cancelled heartbeat is a no-op DONE`() = runTest {
    val runner = runner(emptyList())
    assertEquals(FireOutcome.DONE, runner.handleFire("nope", attempt = 0))
  }

  @Test
  fun `push mode shows notification and records fired`() = runTest {
    db.heartbeatDao().insert(hb(mode = "push"))
    val runner = runner(emptyList())
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    // Пуш ушёл через общий нотифаер, с заголовком напоминания и deep-link в его диалог.
    assertEquals("T", notifier.calls.single().title)
    assertEquals("conv1", notifier.calls.single().conversationId)
  }

  @Test
  fun `agent mode with prompt writes assistant message to conversation`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "проверь задачи"))
    val runner = runner(listOf(StreamEvent.TextDelta("Всё в порядке, задач 3."), StreamEvent.Finish))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    val messages = db.messageDao().getAllOrdered("conv1")
    assertTrue(messages.any { it.role == "assistant" && it.content.contains("задач 3") })
    assertEquals("Всё в порядке, задач 3.", db.heartbeatDao().getById("hb1")?.lastResult)
    assertTrue(notifier.calls.single().preview.contains("задач 3")) // ответ ушёл и пушем
  }

  @Test
  fun `pulse HEARTBEAT_OK stays silent - no message`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = null))
    val runner = runner(listOf(StreamEvent.TextDelta("HEARTBEAT_OK"), StreamEvent.Finish))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 0))
    assertTrue(db.messageDao().getAllOrdered("conv1").isEmpty())
    assertEquals("fired", db.heartbeatDao().getById("hb1")?.status)
    assertTrue(notifier.calls.isEmpty()) // HEARTBEAT_OK = полная тишина, пуша тоже нет
  }

  @Test
  fun `llm error retries then marks error`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "x"))
    val runner = runner(listOf(StreamEvent.Error("network down")))
    assertEquals(FireOutcome.RETRY, runner.handleFire("hb1", attempt = 0))
    assertEquals(FireOutcome.DONE, runner.handleFire("hb1", attempt = 2))
    assertEquals("error", db.heartbeatDao().getById("hb1")?.status)
  }

  @Test
  fun `periodic keeps status scheduled after successful run`() = runTest {
    db.heartbeatDao().insert(hb(mode = "agent", prompt = "x", kind = "periodic"))
    val runner = runner(listOf(StreamEvent.TextDelta("Готово"), StreamEvent.Finish))
    runner.handleFire("hb1", attempt = 0)
    assertEquals("scheduled", db.heartbeatDao().getById("hb1")?.status)
  }
}
```

Примечание: точные конструкторы `StreamEvent.*` сверить с `app/src/main/java/com/example/agent/core/llm/` (Finish может требовать параметр) — поправить тест под реальные сигнатуры, поведение сценариев сохранить.

- [ ] **Step 2: Прогнать — FAIL** (`Unresolved reference: HeartbeatRunner / FireOutcome`)

- [ ] **Step 3: Реализация**

`app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt`:

```kotlin
package com.aiopenpocket.agent.workers

// @trace <TRACE_ID>

import android.util.Log
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentState
import com.aiopenpocket.agent.loop.ReactLoopRunner
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

enum class FireOutcome { DONE, RETRY }

/**
 * Логика срабатывания heartbeat'а — отдельно от [HeartbeatWorker], чтобы тестироваться без
 * WorkManager. push — статичный пуш; agent — headless-ход [ReactLoopRunner] с безопасным подмножеством
 * тулз. Пуш и deep-link в диалог переиспользуют [TurnNotifier] (та же инфраструктура, что у фоновых
 * ходов); «пульс» без задачи молчит на HEARTBEAT_OK.
 */
@Inject
@SingleIn(AppScope::class)
class HeartbeatRunner(
  private val heartbeatDao: HeartbeatDao,
  private val reactLoopRunner: ReactLoopRunner,
  private val toolRegistry: ToolRegistry,
  private val conversationRepository: ConversationRepository,
  private val turnNotifier: TurnNotifier,
) {

  suspend fun handleFire(heartbeatId: String, attempt: Int): FireOutcome {
    val hb = heartbeatDao.getById(heartbeatId) ?: return FireOutcome.DONE
    if (hb.status == "cancelled") return FireOutcome.DONE
    return when (hb.mode) {
      "push" -> firePush(hb)
      "agent" -> fireAgent(hb, attempt)
      else -> FireOutcome.DONE
    }
  }

  private suspend fun firePush(hb: HeartbeatEntity): FireOutcome {
    // Пуш с deep-link в диалог — через общий TurnNotifier (заголовок = имя напоминания, тап → тот же
    // чат). Без POST_NOTIFICATIONS showAgentNotification внутри молча вернёт false; всё равно fired —
    // напоминание отработало, отсутствие разрешения это вопрос настроек, а не сбой heartbeat'а.
    turnNotifier.turnFinished(hb.conversationId, hb.title, hb.message.orEmpty(), failed = false)
    record(hb, ok = true, result = hb.message)
    return FireOutcome.DONE
  }

  private suspend fun fireAgent(hb: HeartbeatEntity, attempt: Int): FireOutcome {
    val agent = MiniAgentCatalog.get(hb.agentId)
    val systemPrompt = buildString {
      append(agent?.systemPrompt ?: "Ты — полезный ассистент AI Open Pocket.")
      append("\n\nСейчас ты работаешь В ФОНЕ по плановому heartbeat — юзер тебя не вызывал. ")
      append("Отвечай кратко и по делу: твой ответ уйдёт сообщением в чат и пушем.")
    }
    val task = hb.prompt ?: PULSE_PROMPT
    val messages = mutableListOf(
      ChatMessage(role = "system", content = systemPrompt),
      ChatMessage(role = "user", content = task),
    )
    val tools = toolRegistry.getSchemasFor(SAFE_TOOL_NAMES)

    val result = reactLoopRunner.run(
      hb.conversationId, messages, tools, LlmProvider.NIM, BG_MAX_ITERATIONS, hb.agentId,
    ) { /* фон: стриминг некому показывать */ }

    if (result.state == AgentState.ERROR) {
      Log.w(TAG, "heartbeat ${hb.id} attempt=$attempt error: ${result.errorMessage}")
      if (attempt < MAX_ATTEMPTS - 1) return FireOutcome.RETRY
      record(hb, ok = false, result = result.errorMessage ?: "LLM error")
      return FireOutcome.DONE
    }

    val text = result.text.trim()
    // «Пульс» (без явной задачи): HEARTBEAT_OK = агенту нечего сказать — полная тишина.
    if (hb.prompt == null && text.contains("HEARTBEAT_OK")) {
      record(hb, ok = true, result = null)
      return FireOutcome.DONE
    }

    conversationRepository.addMessage(hb.conversationId, "assistant", text)
    turnNotifier.turnFinished(hb.conversationId, hb.title, text.take(140), failed = false)
    record(hb, ok = true, result = text.take(200))
    return FireOutcome.DONE
  }

  private suspend fun record(hb: HeartbeatEntity, ok: Boolean, result: String?) {
    val status = when {
      !ok -> "error"
      hb.kind == "periodic" -> "scheduled" // периодика живёт дальше
      else -> "fired"
    }
    heartbeatDao.recordRun(hb.id, status, System.currentTimeMillis(), result)
  }

  companion object {
    private const val TAG = "HeartbeatRunner"
    const val BG_MAX_ITERATIONS = 5
    const val MAX_ATTEMPTS = 3
    /** Чтение + уведомления. Исключены: sms/email/файлы/alarm/сам heartbeat (самовоспроизводство)/render_card. */
    val SAFE_TOOL_NAMES = setOf("memory_tool", "database_query", "calendar", "step_counter", "notifications")
    val PULSE_PROMPT = """
      Это плановый heartbeat. Посмотри доступные данные (память, базу) и реши: есть ли
      что-то полезное или срочное для юзера прямо сейчас? Если есть — коротко сообщи.
      Если нет — ответь ровно: HEARTBEAT_OK
    """.trimIndent()
  }
}
```

`MiniAgentCatalog.get(id)` — готовый lookup в `app/src/main/java/com/example/agent/core/agent/MiniAgent.kt` (`fun get(id: String?): MiniAgent?`), возвращает null для дефолтного ассистента и неизвестных id. Импорт: `com.aiopenpocket.agent.core.agent.MiniAgentCatalog`.

**Deep-link «тап по пушу → диалог» уже реализован turns-фичей** (`MainActivity.ACTION_OPEN_CONVERSATION` + `EXTRA_CONVERSATION_ID`, `AgentTurnNotifier`). HeartbeatRunner НЕ трогает `MainActivity` — он вызывает `turnNotifier.turnFinished(...)`, а `AgentTurnNotifier` уже строит нужный PendingIntent с deep-link. (Это делает исходный Task 6 избыточным — см. Task 6.)

- [ ] **Step 4: Прогнать — PASS** (6 tests)

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.workers.HeartbeatRunnerTest"`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt app/src/test/java/com/aiopenpocket/agent/workers/HeartbeatRunnerTest.kt
git commit -m "feat(heartbeat): HeartbeatRunner — фоновый ход агента с безопасными тулзами

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: HeartbeatWorker — тонкая обёртка + доступ к графу

**Files:**
- Modify: `app/src/main/java/com/example/agent/workers/HeartbeatWorker.kt` (заглушка из Task 3 → реальная логика)
- Modify: `app/src/main/java/com/example/di/AppGraph.kt` (accessor)

**Interfaces:**
- Consumes: `HeartbeatRunner.handleFire` (Task 4), `AiOpenPocketApp.appGraph`.
- Produces: работающий воркер; `AppGraph.heartbeatRunner: HeartbeatRunner`.

- [ ] **Step 1: Добавить accessor в AppGraph**

В `app/src/main/java/com/example/di/AppGraph.kt` (рядом с `userPreferencesRepository`):

```kotlin
val heartbeatRunner: HeartbeatRunner
```

- [ ] **Step 2: Реализовать doWork**

```kotlin
override suspend fun doWork(): Result {
  val heartbeatId = inputData.getString(KEY_HEARTBEAT_ID) ?: return Result.failure()
  // Metro-граф достаём через Application: дефолтная WorkerFactory остаётся,
  // NotificationWorker продолжает работать как раньше.
  val graph = (applicationContext as AiOpenPocketApp).appGraph
  return when (graph.heartbeatRunner.handleFire(heartbeatId, runAttemptCount)) {
    FireOutcome.RETRY -> Result.retry()
    FireOutcome.DONE -> Result.success()
  }
}
```

(добавить импорты `com.aiopenpocket.AiOpenPocketApp`; вся логика уже покрыта тестами Task 4 — воркер это glue без собственного теста).

- [ ] **Step 3: Собрать и прогнать всё**

Run: `./gradlew testDebugUnitTest`
Expected: PASS (весь набор, без регрессий).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/agent/workers/HeartbeatWorker.kt app/src/main/java/com/example/di/AppGraph.kt
git commit -m "feat(heartbeat): HeartbeatWorker поверх HeartbeatRunner

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Тап по пушу открывает диалог — ✅ УЖЕ СДЕЛАНО turns-фичей (SKIP)

**Статус: пропустить, имплементера не дспетчить.** Deep-link «тап по пушу → открыть диалог»
полностью реализован фичей «фоновые ходы» (turns-коммиты на этой ветке): `MainActivity` уже содержит
`openConversationRequest`, `handleOpenConversationIntent`, навигацию `chat/$convId` и константы
`ACTION_OPEN_CONVERSATION` / `EXTRA_CONVERSATION_ID`; `AgentTurnNotifier` строит PendingIntent с этим
deep-link. Task 4 переиспользует всё это через порт `TurnNotifier`, поэтому отдельная работа не нужна.

Контроллеру: отметить Task 6 выполненным в ledger со ссылкой «покрыто turns».

---

### Task 7: Регистрация тула + раздача heartbeat всему ростеру

Тул регистрируется в глобальном `Set<Tool>` (доступен обычному чату) и добавляется в
`toolNames` всех шести мини-агентов — каждый ставит проактивные напоминания в своём домене.
У `reminders` и `languages` строка `"work_manager"` заменяется на `"heartbeat"` (heartbeat
его надстройка: то же расписание + опциональное пробуждение агента). Остальные четыре просто
получают `"heartbeat"` в дополнение к своим тулзам.

**Files:**
- Modify: `app/src/main/java/com/example/di/AgentToolsModule.kt`
- Modify: `app/src/main/java/com/example/agent/core/agent/MiniAgent.kt` (все 6 агентов, строки 32-135)
- Test: `app/src/test/java/com/aiopenpocket/agent/core/agent/MiniAgentCatalogTest.kt` (новый)

**Interfaces:**
- Consumes: multibinding-паттерн `@Provides @IntoSet fun provideX(tool: X): Tool = tool`; `MiniAgentCatalog.all` (список из 6 агентов).
- Produces: тул `heartbeat` доступен глобально и в скоупе всех 6 агентов; ни у одного агента больше нет `work_manager` в `toolNames`.

- [ ] **Step 1: Падающий тест на каталог**

```kotlin
package com.aiopenpocket.agent.core.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class MiniAgentCatalogTest {

  @Test
  fun `every roster agent can set heartbeats`() {
    for (agent in MiniAgentCatalog.all) {
      assertTrue("agent ${agent.id} lacks heartbeat tool", "heartbeat" in agent.toolNames)
      assertTrue("agent ${agent.id} prompt omits heartbeat", agent.systemPrompt.contains("heartbeat"))
    }
  }

  @Test
  fun `heartbeat supersedes work_manager in scoped agents`() {
    // heartbeat — надстройка над work_manager; в скоупе агентов старый тул больше не нужен.
    for (agent in MiniAgentCatalog.all) {
      assertTrue("agent ${agent.id} still scopes work_manager", "work_manager" !in agent.toolNames)
    }
  }
}
```

- [ ] **Step 2: Прогнать — FAIL**

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.MiniAgentCatalogTest"`
Expected: FAIL (агенты пока без heartbeat).

- [ ] **Step 3: Зарегистрировать тул**

В `AgentToolsModule.kt` (рядом с `provideWorkManagerTool`):

```kotlin
@Provides
@IntoSet
fun provideHeartbeatTool(tool: HeartbeatTool): Tool = tool
```

- [ ] **Step 4: Раздать heartbeat агентам**

В `MiniAgent.kt` для каждого из 6 агентов правим `toolNames` и добавляем в `systemPrompt`
ОДНУ доменную строку (перед финальным «Если просьба не из твоего профиля…»). Правки точечные —
менять только `toolNames`-строку и вставлять указанное предложение, остальной промпт не трогать.

**`budget`** — `toolNames = setOf("memory_tool", "sms", "email", "heartbeat", RENDER, LOAD_SKILL)`.
Вставить в промпт:
```
Периодическую сводку по тратам можешь ставить через heartbeat (mode=agent): по расписанию
глянуть расходы и предупредить о перерасходе — после schedule покажи карточку render_card
template="reminder".
```

**`languages`** — `toolNames`: заменить `"work_manager"` на `"heartbeat"` →
`setOf("memory_tool", "srs_tool", "alarm", "heartbeat", LOAD_SKILL, RENDER)`.
Вставить в промпт (после блока про srs_tool):
```
Напоминания о повторениях ставь через heartbeat (mode=agent): по расписанию проверяй
srs_tool due_words и зови повторить подошедшее — после schedule покажи карточку render_card
template="reminder".
```

**`tasks`** — `toolNames = setOf("memory_tool", "heartbeat", RENDER)`.
Вставить в промпт:
```
Проверку задач можешь ставить через heartbeat (mode=agent): например, раз в день напомнить
о просроченных — после schedule покажи карточку render_card template="reminder".
```

**`reminders`** — `toolNames = setOf("alarm", "heartbeat", "calendar", RENDER)` (замена
`work_manager` → `heartbeat`). Заменить в промпте предложение про work_manager на:
```
Напоминания и периодические проверки ставь через heartbeat (mode=push — просто пуш; mode=agent —
фоновая проверка с твоим участием), после schedule показывай карточку render_card
template="reminder".
```
(остальное про alarm/calendar оставить как есть).

**`nutrition`** — `toolNames = setOf("alarm", "memory_tool", "heartbeat", RENDER)`.
Вставить в промпт:
```
Напоминания о приёмах пищи и окнах голодания можешь ставить через heartbeat (mode=push для
простого пуша, mode=agent для проверки) — после schedule покажи карточку render_card
template="reminder".
```

**`diary`** — `toolNames = setOf("file_tool", "memory_tool", "heartbeat", RENDER, LOAD_SKILL)`.
Вставить в промпт:
```
Вечернее напоминание «как прошёл день» ставь через heartbeat (mode=agent): по расписанию мягко
предложи записать день — после schedule покажи карточку render_card template="reminder".
```

- [ ] **Step 5: Прогнать — PASS**

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.MiniAgentCatalogTest"`
Expected: PASS (2 tests, обходят все 6 агентов).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/di/AgentToolsModule.kt app/src/main/java/com/example/agent/core/agent/MiniAgent.kt app/src/test/java/com/aiopenpocket/agent/core/agent/MiniAgentCatalogTest.kt
git commit -m "feat(heartbeat): раздать heartbeat всему ростеру мини-агентов

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: Живая карточка `reminder`

**Files:**
- Modify: `app/src/main/java/com/example/agent/tools/RenderCardTool.kt` (список шаблонов + описание)
- Modify: `app/src/main/java/com/example/ui/chat/DrivenUiEngine.kt` (sum-type + parse + Composable)
- Modify: `app/src/main/java/com/example/ui/chat/ChatViewModel.kt` (observe/cancel)
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt` (прокинуть в рендер карточек)
- Create: `app/src/main/java/com/example/ui/chat/HeartbeatCardFormat.kt`
- Test: расширить `app/src/test/java/com/aiopenpocket/ui/chat/DrivenUiEngineTest.kt` + новый `app/src/test/java/com/aiopenpocket/ui/chat/HeartbeatCardFormatTest.kt`

**Interfaces:**
- Consumes: `HeartbeatDao.observe(id): Flow<HeartbeatEntity?>`, `HeartbeatTool.workName(id)`, паттерны карточек `SwitchCard`/`NotificationPermissionCard` в DrivenUiEngine.
- Produces: `DrivenUiCard.ReminderCard(heartbeatId)`; `ReminderCardView(card, observeHeartbeat, onCancel)`; `formatSchedule(hb, now): String`, `formatStatus(hb): String` (pure).

- [ ] **Step 1: Падающие тесты**

В `DrivenUiEngineTest.kt` добавить:

```kotlin
@Test
fun `parses reminder card`() {
  val spec = """{"template":"reminder","params":{"heartbeat_id":"hb42"}}"""
  val card = DrivenUiEngine.parseJsonString(spec)
  assertTrue(card is DrivenUiCard.ReminderCard)
  assertEquals("hb42", (card as DrivenUiCard.ReminderCard).heartbeatId)
}

@Test
fun `reminder card without id is error card`() {
  val card = DrivenUiEngine.parseJsonString("""{"template":"reminder","params":{}}""")
  assertTrue(card is DrivenUiCard.ErrorCard)
}
```

`app/src/test/java/com/aiopenpocket/ui/chat/HeartbeatCardFormatTest.kt`:

```kotlin
package com.aiopenpocket.ui.chat

import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartbeatCardFormatTest {

  private fun hb(
    kind: String = "one_time",
    fireAt: Long? = null,
    intervalMinutes: Long? = null,
    status: String = "scheduled",
  ) = HeartbeatEntity(
    id = "x", title = "T", mode = "push", conversationId = "c",
    kind = kind, fireAt = fireAt, intervalMinutes = intervalMinutes, status = status,
  )

  @Test
  fun `one-time future formats as relative minutes`() {
    val now = 1_000_000L
    assertEquals("через 30 мин", formatSchedule(hb(fireAt = now + 30 * 60_000L), now))
  }

  @Test
  fun `one-time under 2 hours formats as hours and minutes`() {
    val now = 0L
    assertEquals("через 1 ч 30 мин", formatSchedule(hb(fireAt = 90 * 60_000L), now))
  }

  @Test
  fun `periodic formats as every N`() {
    assertEquals("каждые 30 мин", formatSchedule(hb(kind = "periodic", intervalMinutes = 30), 0L))
    assertEquals("каждые 2 ч", formatSchedule(hb(kind = "periodic", intervalMinutes = 120), 0L))
  }

  @Test
  fun `statuses map to human labels`() {
    assertEquals("Ждёт", formatStatus(hb()))
    assertEquals("Сработало", formatStatus(hb(status = "fired")))
    assertEquals("Отменено", formatStatus(hb(status = "cancelled")))
    assertEquals("Ошибка", formatStatus(hb(status = "error")))
  }
}
```

- [ ] **Step 2: Прогнать — FAIL**, затем реализация

`app/src/main/java/com/example/ui/chat/HeartbeatCardFormat.kt`:

```kotlin
package com.aiopenpocket.ui.chat

// @trace <TRACE_ID>

import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity

fun formatSchedule(hb: HeartbeatEntity, now: Long = System.currentTimeMillis()): String {
  if (hb.kind == "periodic") {
    val m = hb.intervalMinutes ?: 0
    return if (m % 60 == 0L && m >= 60) "каждые ${m / 60} ч" else "каждые $m мин"
  }
  val left = ((hb.fireAt ?: now) - now).coerceAtLeast(0) / 60_000L
  return when {
    left >= 60 -> "через ${left / 60} ч ${left % 60} мин"
    else -> "через $left мин"
  }
}

fun formatStatus(hb: HeartbeatEntity): String = when (hb.status) {
  "fired" -> "Сработало"
  "cancelled" -> "Отменено"
  "error" -> "Ошибка"
  else -> "Ждёт"
}
```

В `RenderCardTool.kt`: добавить `"reminder"` в `CARD_TEMPLATES` и в description:

```
reminder — живая карточка напоминания/heartbeat, params: {heartbeat_id} (id из ответа тула heartbeat).
```

В `DrivenUiEngine.kt`:
1. В sealed-иерархию `DrivenUiCard` добавить `data class ReminderCard(val heartbeatId: String) : DrivenUiCard()` (рядом с SwitchCard).
2. В `parseJsonString`-when:

```kotlin
"reminder" -> {
  val id = p.optString("heartbeat_id", "")
  if (id.isBlank()) DrivenUiCard.ErrorCard("reminder: нет heartbeat_id")
  else DrivenUiCard.ReminderCard(id)
}
```

3. Composable (рядом с `SwitchCardView`, стиль соседей — Row/Column/MaterialTheme):

```kotlin
@Composable
fun ReminderCardView(
  card: DrivenUiCard.ReminderCard,
  observeHeartbeat: (String) -> Flow<HeartbeatEntity?>,
  onCancel: (String) -> Unit,
) {
  val hb by remember(card.heartbeatId) { observeHeartbeat(card.heartbeatId) }
    .collectAsState(initial = null)
  val current = hb ?: run {
    Text("Напоминание не найдено", style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.outline)
    return
  }
  val cancelled = current.status == "cancelled"
  val contentAlpha = if (cancelled) 0.5f else 1f

  Column(
    verticalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier.fillMaxWidth().alpha(contentAlpha),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Icon(
        if (current.mode == "agent") Icons.Default.SmartToy else Icons.Default.Notifications,
        contentDescription = null, tint = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.width(8.dp))
      Column(Modifier.weight(1f)) {
        Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
          "${formatSchedule(current)} · ${formatStatus(current)}",
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline,
        )
      }
    }
    current.lastResult?.takeIf { it.isNotBlank() }?.let {
      Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
    if (!cancelled && current.status == "scheduled") {
      TextButton(onClick = { onCancel(card.heartbeatId) }) {
        Icon(Icons.Default.Close, contentDescription = null)
        Spacer(Modifier.width(4.dp))
        Text("Отменить")
      }
    }
  }
}
```

4. В `ChatViewModel` (инжект `HeartbeatDao` + `Context`/WorkManager уже доступны по месту — сверить конструктор):

```kotlin
fun observeHeartbeat(id: String): Flow<HeartbeatEntity?> = heartbeatDao.observe(id)

fun cancelHeartbeat(id: String) {
  viewModelScope.launch {
    WorkManager.getInstance(appContext).cancelUniqueWork(HeartbeatTool.workName(id))
    heartbeatDao.setStatus(id, "cancelled")
  }
}
```

5. В `ChatScreen.kt` — в месте диспетчеризации карточек (where `is DrivenUiCard.SwitchCard ->` и соседи) добавить ветку:

```kotlin
is DrivenUiCard.ReminderCard -> ReminderCardView(
  card = card,
  observeHeartbeat = viewModel::observeHeartbeat,
  onCancel = viewModel::cancelHeartbeat,
)
```

(если карточки рендерятся через колбэки-параметры, а не viewModel напрямую — прокинуть обе лямбды тем же путём, что `onAction` у SwitchCard).

- [ ] **Step 3: Прогнать — PASS**

Run: `./gradlew testDebugUnitTest --tests "com.aiopenpocket.ui.chat.*"`
Expected: PASS, включая 2 новых parse-теста и 4 формат-теста.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/chat/ app/src/main/java/com/example/agent/tools/RenderCardTool.kt app/src/test/java/com/aiopenpocket/ui/chat/
git commit -m "feat(heartbeat): живая карточка reminder в чате

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: Урок «Heartbeat и cron в агентах»

**Files:**
- Create: `app/src/main/assets/lessons/heartbeat_and_cron.json`
- Modify: `app/src/main/java/com/example/ui/roadmap/RoadmapScreen.kt:167` (список id — вставить после `"goal_mode"`)
- Modify: `app/src/main/java/com/example/ui/roadmap/LibraryViewModel.kt:52` (аналогично)
- Test: существующий `LessonAssetsValidationTest` валидирует все ассеты автоматически.

**Interfaces:**
- Consumes: JSON-контракт уроков (`goal_mode.json` — образец: id/title/description/iconKey/steps, шаг `{type: "text"|"quiz"}`, квиз `{q, options, correct, explain}`).
- Produces: урок id `heartbeat_and_cron` в «пути агента» после goal_mode.

- [ ] **Step 1: Написать урок**

Точную структуру шагов сверить с `goal_mode.json` и `react_loop.json` (типы шагов, поля квиза). Содержимое `heartbeat_and_cron.json`:

```json
{
  "id": "heartbeat_and_cron",
  "title": "Heartbeat и cron",
  "description": "Как агент живёт без твоих сообщений: расписания, фоновые пробуждения и право молчать.",
  "iconKey": "alarm",
  "steps": [
    { "type": "text", "text": "До сих пор агент просыпался только от твоего сообщения. Но полезный ассистент должен уметь действовать и сам: напомнить, проверить, предупредить. Для этого нужны два старых приёма — cron и heartbeat." },
    { "type": "text", "text": "Cron — планировщик из Unix семидесятых: «выполняй команду по расписанию». Каждый день в 9:00 — отчёт, каждые 15 минут — проверка. Расписание жёсткое, действие фиксированное. Это идеально для напоминалок: «в 18:00 покажи пуш „позвони маме“»." },
    { "type": "text", "text": "Heartbeat — «сердцебиение» — из мониторинга серверов: система регулярно подаёт признак жизни. В агентах этот приём развернули: по расписанию будят самого агента, и он РЕШАЕТ, есть ли повод побеспокоить человека. Расписание жёсткое, а действие — на усмотрение агента." },
    { "type": "text", "text": "Ключевая деталь — право молчать. Проснувшись, агент смотрит на данные (задачи, бюджет, память) и, если всё в порядке, отвечает служебным словом HEARTBEAT_OK — и юзер НИЧЕГО не видит. Без этого проактивный агент превращается в спамера. Так работают heartbeat'ы в Claude Code и OpenClaw." },
    { "type": "text", "text": "На Android свои правила: приложение в фоне жёстко ограничено системой. Наш аналог крона — WorkManager: он гарантирует, что задача выполнится, но не гарантирует точную минуту (система экономит батарею, doze-режим может отложить срабатывание). Периодические задачи — не чаще раза в 15 минут." },
    { "type": "text", "text": "И про цену: каждое пробуждение агента — это настоящий вызов LLM, иногда с инструментами. Поэтому у фонового хода есть лимит витков, а набор инструментов урезан до безопасного: читать данные и слать уведомления — можно, слать SMS или плодить новые heartbeat'ы — нельзя." },
    { "type": "quiz", "questions": [
      {
        "q": "Чем heartbeat агента отличается от классического cron?",
        "options": [
          "Cron выполняет фиксированное действие по расписанию, heartbeat будит агента, и тот сам решает, что делать",
          "Heartbeat срабатывает чаще, чем cron",
          "Cron работает только на серверах, heartbeat — только на телефонах"
        ],
        "correct": 0,
        "explain": "Расписание у обоих жёсткое. Разница в действии: у крона оно задано заранее, у heartbeat'а решение принимает сам агент — вплоть до «промолчать»."
      },
      {
        "q": "Зачем агенту ответ HEARTBEAT_OK?",
        "options": [
          "Это проверка, что LLM ещё работает",
          "Так агент говорит «всё в порядке, юзера беспокоить не надо» — и уведомление не показывается",
          "Так агент запрашивает продление лимита витков"
        ],
        "correct": 1,
        "explain": "Право молчать — главное отличие вежливого проактивного агента от спамера: пробуждение было, а беспокойства нет."
      },
      {
        "q": "Почему периодический heartbeat на Android не может срабатывать каждые 5 минут?",
        "options": [
          "LLM не успеет ответить за 5 минут",
          "WorkManager разрешает периодические задачи не чаще раза в 15 минут — система бережёт батарею",
          "Room не выдержит столько записей"
        ],
        "correct": 1,
        "explain": "15 минут — жёсткий лимит WorkManager на периодические задачи; к тому же doze-режим может дополнительно сдвинуть срабатывание."
      }
    ]},
    { "type": "text", "text": "Теперь попробуем вживую. Открой агента «Напоминалки» и скажи: «поставь heartbeat каждые 30 минут — проверяй мои задачи и пиши, только если что-то требует внимания». Увидишь карточку с расписанием и кнопкой отмены, а первое срабатывание придёт пушем. Не забудь потом отменить, если это был просто тест :)" }
  ]
}
```

Примечание: если урочный чат имеет доступ к тулзам (сверить с тем, как ведут себя другие уроки с практикой) — финальный шаг можно заменить на приглашение поставить heartbeat прямо в этом чате. Если нет — оставить отсылку к «Напоминалкам», как выше.

- [ ] **Step 2: Зарегистрировать в roadmap**

В `RoadmapScreen.kt` (список id, строка ~167) и `LibraryViewModel.kt` (строка ~52) — вставить `"heartbeat_and_cron",` после `"goal_mode",`.

- [ ] **Step 3: Прогнать валидацию уроков**

Run: `./gradlew testDebugUnitTest --tests "*LessonAssetsValidation*"`
Expected: PASS — новый ассет прошёл общую валидацию.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/lessons/heartbeat_and_cron.json app/src/main/java/com/example/ui/roadmap/
git commit -m "feat(lessons): урок «Heartbeat и cron в агентах»

Trace: uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: Финальная верификация

**Files:**
- Modify: `AGENTS.md` (таблица тулз ~строка 237: добавить строку `heartbeat`; упоминание «12/13 инструментов» → актуализировать счётчик)

- [ ] **Step 1: Полный прогон тестов**

Run: `./gradlew testDebugUnitTest`
Expected: PASS, 0 failures.

- [ ] **Step 2: traxer check**

Run: `traxer check`
Expected: 0 errors. Stale-warnings по затронутым фичам (mcp-tools, background-tasks, driven-ui-cards, уроки) — blessить только те, чей rationale реально не изменился: `traxer bless <id>`.

- [ ] **Step 3: Обновить AGENTS.md**

В таблицу System Integrations добавить:

```
| `heartbeat` | `schedule` (push/agent, one-time/periodic ≥15 мин), `cancel`, `list`; agent-режим будит ReactLoopRunner в фоне (безопасное подмножество тулз, HEARTBEAT_OK = молчать) | Room `heartbeats` + `HeartbeatWorker` (CoroutineWorker) | `POST_NOTIFICATIONS` |
```

- [ ] **Step 4: Ручной smoke на устройстве/эмуляторе**

```bash
./gradlew installDebug
```

Сценарий: чат «Напоминалки» → «поставь пуш через 16 минут: выпей воды» → карточка появилась, статус «Ждёт» → кнопка «Отменить» работает (карточка сереет) → поставить ещё раз → свернуть приложение → дождаться пуша → тап открывает диалог. Для agent-режима: «каждые 30 минут проверяй задачи» → дождаться первого срабатывания → сообщение в чате + пуш. (WorkManager может сдвинуть время — для ускорения `adb shell am broadcast` не работает; проверить терпением или через `ScheduledWorksScreen`.)

- [ ] **Step 5: Финальный коммит**

```bash
git add AGENTS.md traceability.yaml
git commit -m "docs: heartbeat в AGENTS.md + traxer bless

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
