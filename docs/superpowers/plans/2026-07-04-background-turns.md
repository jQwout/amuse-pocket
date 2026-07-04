# Background Turns (TurnManager) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ход агента живёт в appScope-синглтоне `TurnManager` и дорабатывает после ухода с экрана чата; параллельность по диалогам, бейджи в списке, пуш при завершении в фоне.

**Architecture:** Логика `launchTurn`/`runAgent` переезжает из `ChatViewModel` в `TurnManager` (Metro `@SingleIn(AppScope)`, свой `SupervisorJob`-скоуп). Состояние хода — `TurnSession` на диалог (StateFlow-стрим, трейс, статус, ретрай-контекст). ViewModel — тонкий проектор; «мигание» решается деривацией: пузырь виден, пока Completed-сообщение не приехало в окно ленты (без `pendingHandoffId`).

**Tech Stack:** Kotlin coroutines/Flow, Metro DI (`@ContributesTo`/`@BindingContainer`/`@ContributesBinding`), Room (без изменений схемы), `androidx.lifecycle:lifecycle-process`, Robolectric + kotlinx-coroutines-test.

Спека: `docs/superpowers/specs/2026-07-04-background-turns-design.md`.

## Global Constraints

- **БД не трогаем**: никаких изменений Entity/схемы Room (политика pre-release: единый v1 baseline).
- **Traxer**: фича получает id через `traxer id` (Task 1); каждый коммит несёт трейлер `Trace: <id>`; перед каждым коммитом — `traxer check` (2 stale-warning'а по урокам `ai-academy-onboarding-5d51bb` / `uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2` — чужие, НЕ blessить).
- **Пути**: каталоги — `app/src/main/java/com/example/...`, но пакеты — `com.aiopenpocket...` (особенность репо, сохранять).
- **Стиль**: файлы в `agent/` — отступ 2 пробела; `ui/chat/ChatViewModel.kt` — 4 пробела. Комментарии по-русски, как в соседнем коде.
- **Команды** (Windows PowerShell из корня репо):
  - компиляция: `.\gradlew.bat :app:compileDebugKotlin`
  - тесты класса: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"`
  - все тесты: `.\gradlew.bat :app:testDebugUnitTest`
- **Поведение хода — байт-в-байт как сейчас** (маркер `[[NEED_INFO]]`, карточки `render_card`, пустой ответ, план-бар, гейт стрима ~20 пуб/сек): источник истины — текущий `ChatViewModel.runAgent` (строки 398–528 на момент плана).
- В коммитах не трогать чужие застейдженные правки уроков (20 файлов lessons/quiz сидят в индексе) — `git add` только по явным путям задачи.

---

### Task 1: Зависимость, порты, TurnSession/TurnStatus, traxer id

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/example/agent/loop/TurnPorts.kt`
- Create: `app/src/main/java/com/example/agent/loop/TurnSession.kt`
- Modify: `traceability.yaml`

**Interfaces:**
- Produces: `TurnExecutor`, `TurnPersistence`, `ForegroundState`, `TurnNotifier` (порты для Task 2–4); `TurnSession`, `TurnStatus` (Running/Completed/Failed); трейс-id фичи `<TRACE_ID>` для всех последующих коммитов.

- [ ] **Step 1: Сгенерировать traxer id и завести фичу**

Run: `traxer id`
Expected: новый id вида `fonovye-khody-agenta-XXXXXX` (далее в плане — `<TRACE_ID>`; подставлять его во все маркеры и трейлеры).

Открыть `traceability.yaml`, скопировать структуру полей у соседней записи (например `background-tasks-15a34a`) и добавить запись:

```yaml
  - id: <TRACE_ID>
    title: Фоновые ходы агента (TurnManager)
    rationale: >
      Ход агента (стрим + тул-луп + запись ответа) не должен умирать вместе с экраном чата:
      appScope-синглтон TurnManager держит по сессии на диалог, экран лишь проецирует состояние.
      Параллельность между диалогами, пуш при завершении в фоне. Граница живучести — процесс
      (смерть процесса/смахивание из recents ход не переживает — осознанный трейдофф вместо сервиса).
```

- [ ] **Step 2: Добавить lifecycle-process**

В `gradle/libs.versions.toml`, секция `[libraries]`, рядом с `androidx-lifecycle-runtime-ktx` (строка ~52):

```toml
androidx-lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycleRuntimeKtx" }
```

В `app/build.gradle.kts`, в `dependencies` рядом с другими `libs.androidx.lifecycle.*`:

```kotlin
implementation(libs.androidx.lifecycle.process)
```

- [ ] **Step 3: Создать TurnPorts.kt**

`app/src/main/java/com/example/agent/loop/TurnPorts.kt`:

```kotlin
package com.aiopenpocket.agent.loop

// @trace <TRACE_ID>

import com.aiopenpocket.agent.core.llm.LlmProvider
import kotlinx.coroutines.flow.Flow

/**
 * Порт исполнения хода — узкий шов между TurnManager и AgentLoopOrchestrator,
 * чтобы менеджер тестировался скриптовым фейком без LLM/Room.
 */
fun interface TurnExecutor {
  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
  ): Flow<AgentEvent>
}

/** То немногое из ConversationRepository, что нужно ходу: записать реплики и узнать заголовок. */
interface TurnPersistence {
  suspend fun addUserMessage(conversationId: String, text: String): String
  suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String
  suspend fun conversationTitle(conversationId: String): String?
}

/** Приложение сейчас на экране? Гейт для пуша об окончании фонового хода. */
fun interface ForegroundState {
  fun isInForeground(): Boolean
}

/** Показ уведомления «ход завершился, пока приложение в фоне». */
fun interface TurnNotifier {
  fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean)
}
```

- [ ] **Step 4: Создать TurnSession.kt**

`app/src/main/java/com/example/agent/loop/TurnSession.kt`:

```kotlin
package com.aiopenpocket.agent.loop

// @trace <TRACE_ID>

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Статус хода в диалоге. Отсутствие сессии в мапе менеджера = Idle (хода нет и не было
 * с последнего просмотра). Running → Completed | Failed; отменённый ход просто удаляется.
 */
sealed interface TurnStatus {
  data object Running : TurnStatus

  /**
   * Ход завершился. [insertedMessageId] — id сохранённого ассистентского сообщения; null, если
   * вставки не было (пустой ответ). Пузырь стрима экран держит, ПОКА сообщение не приехало в окно
   * ленты, — это заменяет прежний pendingHandoffId и убирает «мигание» конца хода.
   */
  data class Completed(
    val insertedMessageId: String?,
    val responseTimeMs: Long,
    val completionTokens: Int,
    val contextTokensUsed: Int,
    /** Сообщение об ошибке «пустой ответ» (иначе null). */
    val error: String?,
    val canRetry: Boolean,
    /** План-мод: показан непустой план, ждём «Выполнить»/«Поправить». */
    val planAwaitingApproval: Boolean,
  ) : TurnStatus

  data class Failed(val message: String, val canRetry: Boolean) : TurnStatus
}

/**
 * Состояние одного хода агента, отвязанное от экрана: живёт в TurnManager (appScope), пока
 * пользователь ходит по приложению. Ретрай-контекст тоже здесь — «Повторить» переживает
 * пересоздание ViewModel (раньше терялся при выходе с экрана).
 */
class TurnSession internal constructor(val conversationId: String) {
  internal val statusFlow = MutableStateFlow<TurnStatus>(TurnStatus.Running)
  val status: StateFlow<TurnStatus> = statusFlow.asStateFlow()

  // Стрим — отдельный поток, как раньше в ChatViewModel: дельты частые, рекомпозируем только пузырь.
  internal val streamingTextFlow = MutableStateFlow("")
  val streamingText: StateFlow<String> = streamingTextFlow.asStateFlow()

  internal val toolTraceFlow = MutableStateFlow<List<ToolTrace>>(emptyList())
  val toolTrace: StateFlow<List<ToolTrace>> = toolTraceFlow.asStateFlow()

  internal var job: Job? = null
  internal val streamingBuilder = StringBuilder()
  internal var lastStreamPublishAt = 0L
  internal var responseStartTime = 0L

  // Ретрай-контекст последнего хода (см. ChatViewModel до переезда: lastUserText и др.).
  internal var lastUserText: String? = null
  internal var lastUserMessageId: String? = null
  internal var lastMode: AgentMode = AgentMode.CHAT
  internal var lastPlanApproved: Boolean = false
  internal var lastGoalMaxIterations: Int = 3
}
```

- [ ] **Step 5: Проверить сборку и существующие тесты**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `traxer check`
Expected: 0 errors (2 известных чужих stale-warning'а по урокам допустимы; broken link по `<TRACE_ID>` быть не должно — маркеры уже стоят в двух новых файлах).

- [ ] **Step 6: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/example/agent/loop/TurnPorts.kt app/src/main/java/com/example/agent/loop/TurnSession.kt traceability.yaml traceability.lock
git commit -m @'
feat(turns): порты и сессия фоновых ходов агента

TurnSession/TurnStatus + узкие порты (executor/persistence/foreground/notifier)
под TurnManager; зависимость lifecycle-process.

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

(Если `traxer check`/хук меняет `traceability.lock` — добавить его в этот же коммит, как выше.)

---

### Task 2: TurnManager — конвейер хода (стрим, тулы, завершение, отмена, параллельность)

**Files:**
- Create: `app/src/main/java/com/example/agent/loop/TurnManager.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`

**Interfaces:**
- Consumes: порты и `TurnSession`/`TurnStatus` из Task 1; `AgentEvent`/`AgentResult`/`AgentState`/`ToolTrace` из `AgentLoopOrchestrator.kt`.
- Produces: `class TurnManager(executor: TurnExecutor, persistence: TurnPersistence, foreground: ForegroundState, notifier: TurnNotifier, scope: CoroutineScope)` с API: `sessions: StateFlow<Map<String, TurnSession>>`, `session(conversationId: String): Flow<TurnSession?>`, `launchTurn(conversationId: String, userText: String, mode: AgentMode, planApproved: Boolean, goalMaxIterations: Int)`, `cancel(conversationId: String)`. Приватный `startTurn(..., persistUserMessage: Boolean)` — его расширит Task 3.

- [ ] **Step 1: Написать падающие тесты (ядро)**

Создать `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`:

```kotlin
package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.llm.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TurnManager тестируется на скриптовых фейках портов: без LLM, без Room. Robolectric нужен
 * из-за android.util.Log и android.os.SystemClock внутри менеджера.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TurnManagerTest {

  private class ScriptedExecutor : TurnExecutor {
    data class Call(val convId: String, val message: String, val mode: AgentMode, val planApproved: Boolean)

    val calls = mutableListOf<Call>()
    var cancelledCollections = 0

    /** Скрипт хода: по вызову отдаёт flow событий. По умолчанию — мгновенный пустой финиш. */
    var script: (Call) -> Flow<AgentEvent> = { flowOf(AgentEvent.Completed(finished(""))) }

    override fun execute(
      conversationId: String,
      userMessage: String,
      provider: LlmProvider,
      mode: AgentMode,
      planApproved: Boolean,
      goalMaxIterations: Int,
    ): Flow<AgentEvent> {
      val call = Call(conversationId, userMessage, mode, planApproved)
      calls += call
      return script(call).onCompletion { if (it is CancellationException) cancelledCollections++ }
    }
  }

  private class FakePersistence : TurnPersistence {
    data class Row(val id: String, val convId: String, val role: String, val content: String, val cardSpec: String?)

    val rows = mutableListOf<Row>()
    private var nextId = 0

    override suspend fun addUserMessage(conversationId: String, text: String): String {
      val id = "m${nextId++}"
      rows += Row(id, conversationId, "user", text, null)
      return id
    }

    override suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String {
      val id = "m${nextId++}"
      rows += Row(id, conversationId, "assistant", text, cardSpec)
      return id
    }

    override suspend fun conversationTitle(conversationId: String): String? = "Чат $conversationId"
  }

  private class RecordingNotifier : TurnNotifier {
    data class Shown(val convId: String, val title: String?, val preview: String, val failed: Boolean)

    val shown = mutableListOf<Shown>()

    override fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean) {
      shown += Shown(conversationId, conversationTitle, preview, failed)
    }
  }

  private val executor = ScriptedExecutor()
  private val persistence = FakePersistence()
  private val notifier = RecordingNotifier()
  private var inForeground = true

  private fun TestScope.manager() = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = { inForeground },
    notifier = notifier,
    scope = backgroundScope,
  )

  @Test
  fun `завершённый ход пишет user и assistant в БД и даёт Completed с id ответа`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("Привет!"))) }
    val m = manager()

    m.launchTurn("c1", "Здравствуй", AgentMode.CHAT, planApproved = false, goalMaxIterations = 3)
    advanceUntilIdle()

    assertEquals(listOf("user" to "Здравствуй", "assistant" to "Привет!"), persistence.rows.map { it.role to it.content })
    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertEquals(persistence.rows[1].id, status.insertedMessageId)
    assertNull(status.error)
  }

  @Test
  fun `ход живёт без подписчиков — никто не собирает флоу, ответ всё равно в БД`() = runTest {
    // Никаких collect'ов на session/streamingText — только запуск и время.
    executor.script = { flowOf(AgentEvent.TextDelta("ок"), AgentEvent.Completed(finished("ок"))) }
    manager().launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    assertEquals("ок", persistence.rows.last().content)
  }

  @Test
  fun `новый ход в том же диалоге отменяет предыдущий`() = runTest {
    executor.script = { call ->
      if (call.message == "первый") flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() }
      else flowOf(AgentEvent.Completed(finished("второй ответ")))
    }
    val m = manager()

    m.launchTurn("c1", "первый", AgentMode.CHAT, false, 3)
    advanceUntilIdle()
    m.launchTurn("c1", "второй", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    assertEquals(1, executor.cancelledCollections)
    assertEquals("второй ответ", persistence.rows.last().content)
  }

  @Test
  fun `два диалога крутятся параллельно и оба дописывают`() = runTest {
    executor.script = { call ->
      if (call.convId == "a") flow { emit(AgentEvent.TextDelta("a...")); awaitCancellation() }
      else flowOf(AgentEvent.Completed(finished("ответ b")))
    }
    val m = manager()

    m.launchTurn("a", "вопрос a", AgentMode.CHAT, false, 3)
    m.launchTurn("b", "вопрос b", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    // b завершился, a всё ещё крутится — его никто не отменял.
    assertEquals(0, executor.cancelledCollections)
    assertTrue(m.sessions.value.getValue("a").status.value is TurnStatus.Running)
    assertTrue(m.sessions.value.getValue("b").status.value is TurnStatus.Completed)
    assertEquals("ответ b", persistence.rows.last().content)
  }

  @Test
  fun `cancel гасит ход и убирает сессию`() = runTest {
    executor.script = { flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() } }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()
    m.cancel("c1")
    advanceUntilIdle()

    assertEquals(1, executor.cancelledCollections)
    assertNull(m.sessions.value["c1"])
  }

  @Test
  fun `TextDelta копится, StateChanged STREAMING сбрасывает буфер между витками`() = runTest {
    executor.script = {
      flowOf(
        AgentEvent.StateChanged(AgentState.STREAMING),
        AgentEvent.TextDelta("преамбула витка 1"),
        AgentEvent.StateChanged(AgentState.EXECUTING_TOOL),
        AgentEvent.StateChanged(AgentState.STREAMING),
        AgentEvent.TextDelta("финальный текст"),
        AgentEvent.StateChanged(AgentState.FINISHED),
        AgentEvent.Completed(finished("финальный текст")),
      )
    }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    // Показан текст ТЕКУЩЕГО витка (см. комментарий про укорачивание в ChatViewModel).
    assertEquals("финальный текст", m.sessions.value.getValue("c1").streamingText.value)
  }

  @Test
  fun `ToolCompleted добавляется в трейс сессии по ходу`() = runTest {
    val trace = ToolTrace(iteration = 1, toolName = "get_time", arguments = emptyMap(), result = com.aiopenpocket.agent.core.mcp.ToolResult.Success("12:00"))
    executor.script = {
      flowOf(
        AgentEvent.ToolCompleted("get_time", trace.result, 1, emptyMap()),
        AgentEvent.Completed(finished("сейчас 12:00", listOf(trace))),
      )
    }
    val m = manager()

    m.launchTurn("c1", "который час", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    assertEquals(listOf("get_time"), m.sessions.value.getValue("c1").toolTrace.value.map { it.toolName })
  }

  @Test
  fun `AgentEvent Error переводит сессию в Failed с canRetry`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("сеть упала")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Failed
    assertEquals("сеть упала", status.message)
    assertTrue(status.canRetry)
    // Ответа в БД нет, вопрос пользователя — есть.
    assertEquals(listOf("user"), persistence.rows.map { it.role })
  }

  companion object {
    fun finished(text: String, trace: List<ToolTrace> = emptyList()) = AgentResult(
      text = text,
      toolTrace = trace,
      state = AgentState.FINISHED,
      iterations = 1,
      promptTokensEstimate = 100,
      completionTokensEstimate = 10,
    )
  }
}
```

- [ ] **Step 2: Убедиться, что тесты падают (класс не существует)**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"`
Expected: FAIL — compilation error, `Unresolved reference: TurnManager`.

- [ ] **Step 3: Реализовать TurnManager**

`app/src/main/java/com/example/agent/loop/TurnManager.kt`. Ветки обработки событий — прямой перенос из `ChatViewModel.runAgent` (гейт стрима, NEED_INFO, карточки, пустой ответ):

```kotlin
package com.aiopenpocket.agent.loop

// @trace <TRACE_ID>

import android.os.SystemClock
import android.util.Log
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Ходы агента, отвязанные от жизненного цикла экрана: appScope-синглтон держит по TurnSession на
 * диалог, ход дорабатывает в фоне после ухода с экрана чата. Внутри диалога ходы сериализуются
 * (новый делает cancelAndJoin предыдущего — как прежний launchTurn во ViewModel), между диалогами
 * идут параллельно. Экран — только проектор: подписался на session(convId), отписался — ходу всё равно.
 *
 * Граница живучести — процесс: смерть процесса/смахивание из recents ход не переживает (осознанный
 * трейдофф вместо foreground-сервиса, см. спеку 2026-07-04-background-turns-design.md).
 */
class TurnManager(
  private val executor: TurnExecutor,
  private val persistence: TurnPersistence,
  private val foreground: ForegroundState,
  private val notifier: TurnNotifier,
  private val scope: CoroutineScope,
) {

  /** Guard мапы сессий и подмены job — launchTurn может прилететь с любого потока. */
  private val mutex = Mutex()

  private val sessionsFlow = MutableStateFlow<Map<String, TurnSession>>(emptyMap())

  /** Активные и завершённые-непросмотренные сессии по conversationId (для бейджей списка). */
  val sessions: StateFlow<Map<String, TurnSession>> = sessionsFlow.asStateFlow()

  fun session(conversationId: String): Flow<TurnSession?> =
    sessionsFlow.map { it[conversationId] }.distinctUntilChanged()

  /** Запускает ход: пишет реплику пользователя в историю и гонит агентный цикл до конца. */
  fun launchTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
  ) {
    scope.launch { startTurn(conversationId, userText, mode, planApproved, goalMaxIterations, persistUserMessage = true) }
  }

  /** Останавливает ход и убирает сессию: для UI диалог возвращается в Idle, без ошибки. */
  fun cancel(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@launch
        session.job?.cancel()
        sessionsFlow.update { it - conversationId }
      }
    }
  }

  /**
   * Единая точка старта (отправка/ретрай/одобрение плана). Прошлый ход этого же диалога мог ещё
   * выполнять инструмент или запись — гасим его и ждём внутри нового job, прежде чем стартовать
   * (перенос семантики прежнего ChatViewModel.launchTurn, но с ключом-диалогом вместо одного
   * глобального activeJob).
   */
  internal suspend fun startTurn(
    conversationId: String,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
    persistUserMessage: Boolean,
  ) {
    mutex.withLock {
      val session = sessionsFlow.value[conversationId] ?: TurnSession(conversationId).also { created ->
        sessionsFlow.update { it + (conversationId to created) }
      }
      val previous = session.job
      session.job = scope.launch {
        previous?.cancelAndJoin()
        runTurn(session, userText, mode, planApproved, goalMaxIterations, persistUserMessage)
      }
    }
  }

  private suspend fun runTurn(
    session: TurnSession,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
    persistUserMessage: Boolean,
  ) {
    val convId = session.conversationId
    resetStreaming(session)
    session.toolTraceFlow.value = emptyList()
    session.responseStartTime = System.currentTimeMillis()
    session.statusFlow.value = TurnStatus.Running
    session.lastMode = mode
    session.lastPlanApproved = planApproved
    session.lastGoalMaxIterations = goalMaxIterations
    try {
      if (persistUserMessage) {
        // Новый ход — новая попытка: ретрай-контекст валиден только после успешного addMessage.
        session.lastUserText = null
        session.lastUserMessageId = null
        val userMsgId = persistence.addUserMessage(convId, userText)
        session.lastUserText = userText
        session.lastUserMessageId = userMsgId
      }
      collectTurnEvents(session, userText, mode, planApproved, goalMaxIterations)
    } catch (e: CancellationException) {
      // Stop / новый ход: отмена — штатный путь.
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "Agent turn error", e)
      finishFailed(session, e.message ?: "Unknown error")
    }
  }

  /** Проекция событий агентного цикла в сессию — прямой перенос веток из ChatViewModel.runAgent. */
  private suspend fun collectTurnEvents(
    session: TurnSession,
    userText: String,
    mode: AgentMode,
    planApproved: Boolean,
    goalMaxIterations: Int,
  ) {
    // Временно гоним всё на NIM; выбор провайдера (GROQ/GITHUB) выпилим позже.
    val flow = executor.execute(session.conversationId, userText, LlmProvider.NIM, mode, planApproved, goalMaxIterations)
    flow.collect { event ->
      when (event) {
        is AgentEvent.TextDelta -> {
          // Аккумулируем в StringBuilder и публикуем под гейтом частоты (~20 пуб/сек):
          // toString() копирует весь накопленный текст, чаще глазу не нужно.
          session.streamingBuilder.append(event.content)
          val now = SystemClock.uptimeMillis()
          if (now - session.lastStreamPublishAt >= STREAM_PUBLISH_INTERVAL_MS) {
            session.lastStreamPublishAt = now
            session.streamingTextFlow.value = session.streamingBuilder.toString()
          }
        }

        is AgentEvent.ToolCompleted -> {
          val newTrace = ToolTrace(
            iteration = event.iteration,
            toolName = event.name,
            arguments = event.arguments,
            result = event.result,
          )
          session.toolTraceFlow.update { it + newTrace }
        }

        is AgentEvent.Completed -> handleCompleted(session, mode, planApproved, event.result)

        is AgentEvent.Error -> finishFailed(session, event.message)

        is AgentEvent.StateChanged -> when (event.state) {
          // Новый виток ReAct стримит свой текст заново — показываем текст ТЕКУЩЕГО витка.
          AgentState.STREAMING -> resetStreaming(session)
          // Стрим витка закончился: дофлашиваем хвост, не прошедший гейт частоты.
          else -> if (session.streamingBuilder.isNotEmpty()) {
            session.streamingTextFlow.value = session.streamingBuilder.toString()
          }
        }

        else -> { /* no-op */
        }
      }
    }
  }

  private suspend fun handleCompleted(
    session: TurnSession,
    mode: AgentMode,
    planApproved: Boolean,
    result: AgentResult,
  ) {
    val convId = session.conversationId
    // render_card-спеки становятся инлайн-карточками чата.
    val cardSpecs = result.toolTrace
      .filter { it.toolName == "render_card" }
      .mapNotNull { (it.result as? ToolResult.Success)?.data }

    // Фаза планирования: маркер [[NEED_INFO]] В НАЧАЛЕ ответа = уточняющий вопрос, не план.
    val planningPhase = mode == AgentMode.PLAN && !planApproved
    val rawText = result.text
    val needInfo = planningPhase && rawText.trimStart().startsWith(NEED_INFO_MARKER)
    val responseText = when {
      needInfo -> rawText.trimStart().removePrefix(NEED_INFO_MARKER).trim()
        .ifBlank { "Уточни, пожалуйста, детали задачи — что именно нужно сделать?" }

      planningPhase -> rawText.trim()
      else -> rawText
    }
    val insertedId = if (responseText.isNotBlank() || cardSpecs.isNotEmpty()) {
      persistence.addAssistantMessage(convId, responseText, cardSpecs.firstOrNull())
    } else {
      null
    }
    cardSpecs.drop(1).forEach { spec ->
      persistence.addAssistantMessage(convId, "", spec)
    }

    val planningDone = planningPhase && !needInfo && responseText.isNotBlank()
    val blankReply = responseText.isBlank() && cardSpecs.isEmpty()
    val blankError = when {
      planningPhase && blankReply -> "Модель не вернула план — попробуй ещё раз."
      blankReply -> "Модель вернула пустой ответ — попробуй ещё раз."
      else -> null
    }

    session.toolTraceFlow.value = result.toolTrace
    // streamingText НЕ сбрасываем: экран держит пузырь, пока сообщение не приедет в окно ленты
    // (замена pendingHandoffId — «мигание» конца хода не возвращается).
    session.statusFlow.value = TurnStatus.Completed(
      insertedMessageId = insertedId,
      responseTimeMs = System.currentTimeMillis() - session.responseStartTime,
      completionTokens = result.completionTokensEstimate,
      contextTokensUsed = result.promptTokensEstimate,
      error = blankError,
      canRetry = blankReply && session.lastUserText != null,
      planAwaitingApproval = planningDone,
    )
    notifyIfBackground(convId, preview = responseText.take(140).ifBlank { "Готово" }, failed = false)
  }

  private suspend fun finishFailed(session: TurnSession, message: String) {
    session.statusFlow.value = TurnStatus.Failed(message, canRetry = session.lastUserText != null)
    notifyIfBackground(session.conversationId, preview = message, failed = true)
  }

  /** Пуш — только когда приложение реально в фоне; внутри приложения — бейджи списка диалогов. */
  private suspend fun notifyIfBackground(conversationId: String, preview: String, failed: Boolean) {
    if (foreground.isInForeground()) return
    val title = runCatching { persistence.conversationTitle(conversationId) }.getOrNull()
    notifier.turnFinished(conversationId, title, preview, failed)
  }

  private fun resetStreaming(session: TurnSession) {
    session.streamingBuilder.setLength(0)
    session.streamingTextFlow.value = ""
    session.lastStreamPublishAt = 0L
  }

  companion object {
    private const val TAG = "TurnManager"

    /** Маркер план-планирования: модель ставит его в начало ответа-вопроса (вырезается из текста). */
    internal const val NEED_INFO_MARKER = "[[NEED_INFO]]"

    /** Минимальный интервал публикации стрим-текста (~20 обновлений/сек). */
    private const val STREAM_PUBLISH_INTERVAL_MS = 50L
  }
}
```

- [ ] **Step 4: Прогнать тесты**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"`
Expected: PASS (8 тестов).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/agent/loop/TurnManager.kt app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt traceability.lock
git commit -m @'
feat(turns): TurnManager — конвейер хода в appScope

Перенос runAgent/launchTurn из ChatViewModel: стрим с гейтом, тул-трейс,
NEED_INFO/карточки/пустой ответ, сериализация внутри диалога, параллельность
между диалогами, cancel. Покрыт тестами на скриптовых фейках портов.

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 3: Ретрай, инвалидация, план-бар, markSeen, бейджи-флоу

**Files:**
- Modify: `app/src/main/java/com/example/agent/loop/TurnManager.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`

**Interfaces:**
- Produces (новые методы `TurnManager`, их ждёт Task 5/6):
  - `retry(conversationId: String)` — перезапуск последнего хода БЕЗ повторного addMessage
  - `approvePlan(conversationId: String)` — ход «Одобряю план…» с planApproved=true
  - `dismissPlanApproval(conversationId: String)` — скрыть план-бар (интент EditPlan)
  - `invalidateRetry(conversationId: String)` — сброс ретрай-контекста (интент SetMode)
  - `invalidateRetryFor(conversationId: String, messageId: String)` — сброс, только если удалили то самое сообщение
  - `clearError(conversationId: String)` — убрать Failed/blank-error сессию (интент ClearError)
  - `markSeen(conversationId: String)` — убрать «чисто завершённую» сессию (вход в диалог / ответ доехал до ленты)
  - `val badges: Flow<Map<String, TurnStatus>>` — статусы по диалогам для списка

- [ ] **Step 1: Дописать падающие тесты**

Добавить в `TurnManagerTest.kt`:

```kotlin
  @Test
  fun `retry перезапускает ход без повторного addMessage`() = runTest {
    var failFirst = true
    executor.script = {
      if (failFirst) { failFirst = false; flowOf(AgentEvent.Error("таймаут")) }
      else flowOf(AgentEvent.Completed(finished("со второго раза")))
    }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()
    m.retry("c1")
    advanceUntilIdle()

    // user-реплика одна, ответ дописан; повторный ход шёл с тем же текстом.
    assertEquals(listOf("user" to "вопрос", "assistant" to "со второго раза"), persistence.rows.map { it.role to it.content })
    assertEquals(2, executor.calls.size)
    assertEquals("вопрос", executor.calls[1].message)
    assertTrue(m.sessions.value.getValue("c1").status.value is TurnStatus.Completed)
  }

  @Test
  fun `approvePlan запускает фазу выполнения с planApproved`() = runTest {
    executor.script = { call ->
      if (!call.planApproved) flowOf(AgentEvent.Completed(finished("1. шаг один")))
      else flowOf(AgentEvent.Completed(finished("сделано")))
    }
    val m = manager()

    m.launchTurn("c1", "сделай X", AgentMode.PLAN, planApproved = false, goalMaxIterations = 3)
    advanceUntilIdle()
    assertTrue((m.sessions.value.getValue("c1").status.value as TurnStatus.Completed).planAwaitingApproval)

    m.approvePlan("c1")
    advanceUntilIdle()

    val exec = executor.calls[1]
    assertEquals(AgentMode.PLAN, exec.mode)
    assertTrue(exec.planApproved)
    // Реплика одобрения записана в историю.
    assertTrue(persistence.rows.any { it.role == "user" && it.content == "Одобряю план, выполни его по шагам." })
  }

  @Test
  fun `NEED_INFO в фазе планирования вырезается и не показывает план-бар`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("[[NEED_INFO]] Какой именно отчёт нужен?"))) }
    val m = manager()

    m.launchTurn("c1", "сделай отчёт", AgentMode.PLAN, planApproved = false, goalMaxIterations = 3)
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertTrue(!status.planAwaitingApproval)
    assertEquals("Какой именно отчёт нужен?", persistence.rows.last().content)
  }

  @Test
  fun `пустой ответ даёт Completed с ошибкой и canRetry без вставки в БД`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished(""))) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    val status = m.sessions.value.getValue("c1").status.value as TurnStatus.Completed
    assertNull(status.insertedMessageId)
    assertEquals("Модель вернула пустой ответ — попробуй ещё раз.", status.error)
    assertTrue(status.canRetry)
    assertEquals(listOf("user"), persistence.rows.map { it.role })
  }

  @Test
  fun `render_card - первая карточка в сообщении, остальные отдельными`() = runTest {
    val card1 = ToolTrace(1, "render_card", emptyMap(), com.aiopenpocket.agent.core.mcp.ToolResult.Success("{\"type\":\"todo\"}"))
    val card2 = ToolTrace(1, "render_card", emptyMap(), com.aiopenpocket.agent.core.mcp.ToolResult.Success("{\"type\":\"table\"}"))
    executor.script = { flowOf(AgentEvent.Completed(finished("вот карточки", listOf(card1, card2)))) }
    val m = manager()

    m.launchTurn("c1", "покажи", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    val assistant = persistence.rows.filter { it.role == "assistant" }
    assertEquals(2, assistant.size)
    assertEquals("{\"type\":\"todo\"}", assistant[0].cardSpec)
    assertEquals("" to "{\"type\":\"table\"}", assistant[1].content to assistant[1].cardSpec)
  }

  @Test
  fun `invalidateRetryFor гасит ретрай только для того самого сообщения`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("упало")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()
    val userMsgId = persistence.rows.first().id

    m.invalidateRetryFor("c1", "чужой-id")
    advanceUntilIdle()
    assertTrue((m.sessions.value.getValue("c1").status.value as TurnStatus.Failed).canRetry)

    m.invalidateRetryFor("c1", userMsgId)
    advanceUntilIdle()
    assertTrue(!(m.sessions.value.getValue("c1").status.value as TurnStatus.Failed).canRetry)
  }

  @Test
  fun `clearError убирает Failed-сессию`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("упало")) }
    val m = manager()

    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()
    m.clearError("c1")
    advanceUntilIdle()

    assertNull(m.sessions.value["c1"])
  }

  @Test
  fun `markSeen убирает чисто завершённую сессию, но не Running и не план-бар`() = runTest {
    executor.script = { flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() } }
    val m = manager()
    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    m.markSeen("c1")
    advanceUntilIdle()
    assertTrue(m.sessions.value.containsKey("c1")) // Running не трогаем

    executor.script = { flowOf(AgentEvent.Completed(finished("готово"))) }
    m.launchTurn("c1", "ещё вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()
    m.markSeen("c1")
    advanceUntilIdle()
    assertNull(m.sessions.value["c1"]) // чистое Completed — убрали

    executor.script = { flowOf(AgentEvent.Completed(finished("1. план"))) }
    m.launchTurn("c1", "сделай X", AgentMode.PLAN, false, 3)
    advanceUntilIdle()
    m.markSeen("c1")
    advanceUntilIdle()
    assertTrue(m.sessions.value.containsKey("c1")) // план ждёт одобрения — бейдж/бар живут
  }

  @Test
  fun `dismissPlanApproval прячет план-бар не убивая сессию`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("1. план"))) }
    val m = manager()

    m.launchTurn("c1", "сделай X", AgentMode.PLAN, false, 3)
    advanceUntilIdle()
    m.dismissPlanApproval("c1")
    advanceUntilIdle()

    assertTrue(!(m.sessions.value.getValue("c1").status.value as TurnStatus.Completed).planAwaitingApproval)
  }

  @Test
  fun `badges отдаёт статусы по всем диалогам`() = runTest {
    executor.script = { call ->
      if (call.convId == "a") flow { emit(AgentEvent.TextDelta("...")); awaitCancellation() }
      else flowOf(AgentEvent.Completed(finished("ответ")))
    }
    val m = manager()

    m.launchTurn("a", "вопрос", AgentMode.CHAT, false, 3)
    m.launchTurn("b", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    val badges = mutableListOf<Map<String, TurnStatus>>()
    val job = backgroundScope.launch { m.badges.collect { badges += it } }
    advanceUntilIdle()

    assertTrue(badges.last()["a"] is TurnStatus.Running)
    assertTrue(badges.last()["b"] is TurnStatus.Completed)
    job.cancel()
  }
```

В импорты тестового файла добавить `import kotlinx.coroutines.launch` (нужен тесту `badges`).

- [ ] **Step 2: Убедиться, что новые тесты падают**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"`
Expected: FAIL — `Unresolved reference: retry` (и остальные новые методы).

- [ ] **Step 3: Дописать методы TurnManager**

Добавить в `TurnManager` (после `cancel`), импортировав дополнительно `kotlinx.coroutines.ExperimentalCoroutinesApi`, `kotlinx.coroutines.flow.combine`, `kotlinx.coroutines.flow.flatMapLatest`, `kotlinx.coroutines.flow.flowOf`:

```kotlin
  /** Повтор последнего хода после ошибки: реплика пользователя уже в истории, addMessage не повторяем. */
  fun retry(conversationId: String) {
    scope.launch {
      val session = sessionsFlow.value[conversationId] ?: return@launch
      val text = session.lastUserText ?: return@launch
      startTurn(conversationId, text, session.lastMode, session.lastPlanApproved, session.lastGoalMaxIterations, persistUserMessage = false)
    }
  }

  /** План-мод: пользователь одобрил план → фаза выполнения (инструменты включены). */
  fun approvePlan(conversationId: String) {
    scope.launch {
      val goalIterations = sessionsFlow.value[conversationId]?.lastGoalMaxIterations ?: 3
      startTurn(conversationId, APPROVE_PLAN_MESSAGE, AgentMode.PLAN, planApproved = true, goalMaxIterations = goalIterations, persistUserMessage = true)
    }
  }

  /** «Поправить» вместо «Выполнить»: бар одобрения прячем, сессию и ретрай не трогаем. */
  fun dismissPlanApproval(conversationId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    session.statusFlow.update { status ->
      if (status is TurnStatus.Completed) status.copy(planAwaitingApproval = false) else status
    }
  }

  /** Смена режима агента инвалидирует «Повторить» прошлого хода (иначе ретрай уйдёт в старый режим). */
  fun invalidateRetry(conversationId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    session.lastUserText = null
    session.lastUserMessageId = null
    session.statusFlow.update { status ->
      when (status) {
        is TurnStatus.Failed -> status.copy(canRetry = false)
        is TurnStatus.Completed -> status.copy(canRetry = false)
        else -> status
      }
    }
  }

  /** Удалили сообщение, которое держит «Повторить», — гасим ретрай (см. deleteMessage до переезда). */
  fun invalidateRetryFor(conversationId: String, messageId: String) {
    val session = sessionsFlow.value[conversationId] ?: return
    if (session.lastUserMessageId == messageId) invalidateRetry(conversationId)
  }

  /** Интент ClearError: пользователь закрыл баннер ошибки — сессия с ошибкой больше не нужна. */
  fun clearError(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@launch
        val status = session.status.value
        val hasError = status is TurnStatus.Failed || (status is TurnStatus.Completed && status.error != null)
        if (hasError) {
          session.job?.cancel()
          sessionsFlow.update { it - conversationId }
        }
      }
    }
  }

  /**
   * Ход просмотрен (вход в диалог, либо ответ доехал до ленты) — чисто завершённую сессию убираем,
   * чтобы бейдж «готово» в списке погас. Running/Failed/план-бар/blank-error не трогаем: они ещё
   * нужны экрану (индикация, ретрай, одобрение).
   */
  fun markSeen(conversationId: String) {
    scope.launch {
      mutex.withLock {
        val session = sessionsFlow.value[conversationId] ?: return@launch
        val status = session.status.value
        val cleanlyDone = status is TurnStatus.Completed &&
          status.error == null && !status.planAwaitingApproval && !status.canRetry
        if (cleanlyDone) {
          sessionsFlow.update { it - conversationId }
        }
      }
    }
  }

  /** Статусы ходов по диалогам — бейджи списка диалогов («варится» / «готово» / «ошибка»). */
  @OptIn(ExperimentalCoroutinesApi::class)
  val badges: Flow<Map<String, TurnStatus>> = sessionsFlow.flatMapLatest { map ->
    if (map.isEmpty()) {
      flowOf(emptyMap())
    } else {
      combine(map.map { (id, session) -> session.status.map { id to it } }) { pairs -> pairs.toMap() }
    }
  }
```

И в `companion object` добавить:

```kotlin
    /** Реплика, которой ход одобрения плана попадает в историю (перенос из ChatViewModel.approvePlan). */
    internal const val APPROVE_PLAN_MESSAGE = "Одобряю план, выполни его по шагам."
```

- [ ] **Step 4: Прогнать тесты**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"`
Expected: PASS (18 тестов).

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/agent/loop/TurnManager.kt app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt traceability.lock
git commit -m @'
feat(turns): ретрай, план-бар, markSeen и бейджи в TurnManager

retry/approvePlan/dismissPlanApproval/invalidateRetry(For)/clearError/markSeen +
badges-флоу для списка диалогов. Ретрай-контекст теперь переживает пересоздание экрана.

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 4: Уведомления, DI-модуль, deep-link из пуша

**Files:**
- Create: `app/src/main/java/com/example/agent/core/notification/AppForegroundTracker.kt`
- Create: `app/src/main/java/com/example/agent/core/notification/AgentTurnNotifier.kt`
- Create: `app/src/main/java/com/example/di/TurnModule.kt`
- Modify: `app/src/main/java/com/example/MainActivity.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`

**Interfaces:**
- Consumes: `ForegroundState`, `TurnNotifier` (Task 1), `TurnManager` (Task 2/3), `showAgentNotification` (существующий `NotificationHelper.kt`).
- Produces: DI-биндинги — `TurnManager` инжектится как обычная зависимость (нужно Task 5/6); `MainActivity.ACTION_OPEN_CONVERSATION`, `MainActivity.EXTRA_CONVERSATION_ID`.

- [ ] **Step 1: Написать падающий тест «пуш только в фоне»**

Добавить в `TurnManagerTest.kt`:

```kotlin
  @Test
  fun `уведомление уходит только когда приложение в фоне`() = runTest {
    executor.script = { flowOf(AgentEvent.Completed(finished("готово"))) }
    val m = manager()

    inForeground = true
    m.launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()
    assertEquals(0, notifier.shown.size)

    inForeground = false
    m.launchTurn("c2", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    assertEquals(1, notifier.shown.size)
    val shown = notifier.shown.single()
    assertEquals("c2", shown.convId)
    assertEquals("Чат c2", shown.title)
    assertEquals("готово", shown.preview)
    assertTrue(!shown.failed)
  }

  @Test
  fun `падение хода в фоне тоже уведомляет`() = runTest {
    executor.script = { flowOf(AgentEvent.Error("сеть упала")) }
    inForeground = false
    manager().launchTurn("c1", "вопрос", AgentMode.CHAT, false, 3)
    advanceUntilIdle()

    val shown = notifier.shown.single()
    assertTrue(shown.failed)
    assertEquals("сеть упала", shown.preview)
  }
```

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"`
Expected: эти два теста PASS сразу (логика notifyIfBackground уже написана в Task 2) — это фиксация контракта. Если FAIL — чинить менеджер, не тест.

- [ ] **Step 2: AppForegroundTracker**

`app/src/main/java/com/example/agent/core/notification/AppForegroundTracker.kt`:

```kotlin
package com.aiopenpocket.agent.core.notification

// @trace <TRACE_ID>

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * «Приложение на экране?» через ProcessLifecycleOwner: STARTED и выше — любой экран приложения
 * виден. Гейт пуша об окончании фонового хода: внутри приложения вместо пуша — бейджи списка.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AppForegroundTracker : ForegroundState {
  override fun isInForeground(): Boolean =
    ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
}
```

- [ ] **Step 3: AgentTurnNotifier**

`app/src/main/java/com/example/agent/core/notification/AgentTurnNotifier.kt`:

```kotlin
package com.aiopenpocket.agent.core.notification

// @trace <TRACE_ID>

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import com.aiopenpocket.MainActivity
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Пуш «агент ответил/ход упал», когда приложение в фоне. Тап ведёт в тот самый диалог.
 * notificationId = hashCode диалога: новое уведомление того же чата заменяет старое.
 * Без разрешения POST_NOTIFICATIONS showAgentNotification молча вернёт false — ответ всё
 * равно сохранён в истории.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AgentTurnNotifier(private val application: Application) : TurnNotifier {

  override fun turnFinished(conversationId: String, conversationTitle: String?, preview: String, failed: Boolean) {
    val intent = Intent(application, MainActivity::class.java).apply {
      action = MainActivity.ACTION_OPEN_CONVERSATION
      putExtra(MainActivity.EXTRA_CONVERSATION_ID, conversationId)
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentIntent = PendingIntent.getActivity(
      application,
      conversationId.hashCode(),
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    showAgentNotification(
      context = application,
      title = if (failed) "Ход не удался" else (conversationTitle ?: "Агент ответил"),
      message = preview,
      notificationId = conversationId.hashCode(),
      contentIntent = contentIntent,
    )
  }
}
```

- [ ] **Step 4: TurnModule (DI)**

`app/src/main/java/com/example/di/TurnModule.kt`:

```kotlin
package com.aiopenpocket.di

// @trace <TRACE_ID>

import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.loop.AgentLoopOrchestrator
import com.aiopenpocket.agent.loop.ForegroundState
import com.aiopenpocket.agent.loop.TurnExecutor
import com.aiopenpocket.agent.loop.TurnManager
import com.aiopenpocket.agent.loop.TurnNotifier
import com.aiopenpocket.agent.loop.TurnPersistence
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@ContributesTo(AppScope::class)
@BindingContainer
object TurnModule {

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnExecutor(orchestrator: AgentLoopOrchestrator): TurnExecutor =
    TurnExecutor { conversationId, message, provider, mode, planApproved, goalMaxIterations ->
      orchestrator.execute(conversationId, message, provider, mode, planApproved, goalMaxIterations)
    }

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnPersistence(repository: ConversationRepository): TurnPersistence = object : TurnPersistence {
    override suspend fun addUserMessage(conversationId: String, text: String): String =
      repository.addMessage(conversationId, "user", text)

    override suspend fun addAssistantMessage(conversationId: String, text: String, cardSpec: String?): String =
      repository.addMessage(conversationId, "assistant", text, cardSpec = cardSpec)

    override suspend fun conversationTitle(conversationId: String): String? =
      repository.getConversation(conversationId)?.title
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideTurnManager(
    executor: TurnExecutor,
    persistence: TurnPersistence,
    foreground: ForegroundState,
    notifier: TurnNotifier,
  ): TurnManager = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = foreground,
    notifier = notifier,
    // Свой скоуп уровня приложения: ход агента живёт, пока жив процесс, а не экран.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
  )
}
```

- [ ] **Step 5: MainActivity — открытие диалога из пуша**

В `app/src/main/java/com/example/MainActivity.kt`:

1. Рядом с `openNewChatRequest` (строка ~50) добавить:

```kotlin
    // Id диалога из тапа по уведомлению «агент ответил» — сигнал UI открыть этот чат.
    private val openConversationRequest = MutableStateFlow<String?>(null)
```

2. В `onCreate` после блока `if (isOpenNewChatIntent(intent)) {...}` добавить `handleOpenConversationIntent(intent)`; в `onNewIntent` после того же блока — `handleOpenConversationIntent(intent)`.

3. Приватный метод рядом с `isOpenNewChatIntent`:

```kotlin
    private fun handleOpenConversationIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_CONVERSATION) {
            intent.getStringExtra(EXTRA_CONVERSATION_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { openConversationRequest.value = it }
        }
    }
```

4. В `setContent`, рядом с существующим `LaunchedEffect(newChatToken, onboardingCompleted)` (строка ~115), добавить:

```kotlin
                            // Тап по уведомлению «агент ответил»: открываем тот самый диалог.
                            val openConversationId by openConversationRequest.collectAsState()
                            LaunchedEffect(openConversationId, onboardingCompleted) {
                                val convId = openConversationId
                                if (convId != null && onboardingCompleted == true) {
                                    navController.navigate("chat/$convId") { launchSingleTop = true }
                                    openConversationRequest.value = null
                                }
                            }
```

5. В `companion object` (строка ~282):

```kotlin
        /** Тап по уведомлению «агент ответил» — открыть конкретный диалог. */
        const val ACTION_OPEN_CONVERSATION = "com.aiopenpocket.action.OPEN_CONVERSATION"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
```

- [ ] **Step 6: Сборка и тесты**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Metro-граф собрался: биндинги ForegroundState/TurnNotifier + TurnModule).

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnManagerTest"`
Expected: PASS (20 тестов).

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/example/agent/core/notification/AppForegroundTracker.kt app/src/main/java/com/example/agent/core/notification/AgentTurnNotifier.kt app/src/main/java/com/example/di/TurnModule.kt app/src/main/java/com/example/MainActivity.kt app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt traceability.lock
git commit -m @'
feat(turns): пуш о завершении фонового хода + DI + deep-link

AppForegroundTracker (lifecycle-process), AgentTurnNotifier поверх готового
NotificationHelper, TurnModule с appScope-скоупом менеджера; тап по пушу
открывает диалог через ACTION_OPEN_CONVERSATION.

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 5: ChatViewModel — тонкий проектор

**Files:**
- Modify: `app/src/main/java/com/example/ui/chat/ChatViewModel.kt`

**Interfaces:**
- Consumes: весь API `TurnManager` из Task 2–3 (инжект конструктором: `private val turnManager: TurnManager`).
- Produces: публичный контракт ViewModel НЕ меняется (`state: StateFlow<ChatUiState>`, `streamingText: StateFlow<String>`, `handleIntent`, `setConversationId`, `sendUserMessage`, `completeLesson`, `setSearchActive`, `setSearchQuery`, `loadOlderMessages`) — ChatScreen не трогаем.

Экранное (ввод, голос, уроки, поиск, пагинация) не трогать. Меняется только турн-механика.

- [ ] **Step 1: Удалить переехавшее**

Удалить из `ChatViewModel`:
- поля: `streamingBuilder`, `_streamingText`, `streamingText` (старую реализацию), `lastStreamPublishAt`, `activeJob`, `pendingHandoffId`, `lastUserText`, `lastUserMessageId`, `lastMode`, `lastPlanApproved`, `responseStartTime`
- методы: `launchTurn`, `runAgent`, `resetStreaming`, старые тела `sendMessage`/`approvePlan`/`retry`/`cancel`
- из `companion object`: `NEED_INFO_MARKER`, `STREAM_PUBLISH_INTERVAL_MS`
- зависимость `agentLoop: AgentLoopOrchestrator` из конструктора (её место займёт `turnManager`)
- неиспользуемые импорты (`SystemClock`, `AgentEvent`, `AgentLoopOrchestrator`, `AgentState`, `ToolResult`, `LlmProvider`, `cancelAndJoin` и т.п. — по подсказкам компилятора в конце)

- [ ] **Step 2: Новый конструктор и поля**

Конструктор: заменить `private val agentLoop: AgentLoopOrchestrator` на `private val turnManager: TurnManager` (импорт `com.aiopenpocket.agent.loop.TurnManager`, `com.aiopenpocket.agent.loop.TurnStatus`, `kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.flowOf`, `kotlinx.coroutines.flow.stateIn`).

Новые поля (вместо удалённых):

```kotlin
    /** Текущий conversationId для подписок на сессию хода (пустой = чат ещё не создан). */
    private val convIdFlow = MutableStateFlow("")
    private var sessionJob: Job? = null

    // Стрим-текст берём напрямую из сессии хода в TurnManager: ход (и его стрим) живёт в appScope,
    // а этот флоу лишь переключается на сессию текущего диалога — вернулся на экран, стрим продолжает бежать.
    @OptIn(ExperimentalCoroutinesApi::class)
    val streamingText: StateFlow<String> = convIdFlow
        .flatMapLatest { id -> if (id.isBlank()) flowOf(null) else turnManager.session(id) }
        .flatMapLatest { session -> session?.streamingText ?: flowOf("") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
```

- [ ] **Step 3: Проекция сессии в ChatUiState**

Добавить метод (рядом с `observeMessages`):

```kotlin
    /**
     * Проекция сессии хода из TurnManager в состояние экрана. Ход живёт в appScope — экран лишь
     * отражает его. «Мигание» конца хода решается деривацией: пузырь (isLoading) держим, пока
     * Completed-сообщение не приехало в окно ленты, — подмена атомарна, т.к. messages и статус
     * сходятся в одном combine и одном _state.update.
     */
    private fun observeSession(convId: String) {
        sessionJob?.cancel()
        sessionJob = viewModelScope.launch {
            turnManager.session(convId).collectLatest { session ->
                if (session == null) {
                    // Сессии нет (не было хода / отменён / просмотрен) — только гасим индикацию,
                    // остальное состояние (сообщения, статистика) не трогаем.
                    _state.update { it.copy(isLoading = false, planAwaitingApproval = false) }
                    return@collectLatest
                }
                combine(
                    session.status,
                    session.toolTrace,
                    _state.map { it.messages }.distinctUntilChanged(),
                ) { status, trace, messages -> Triple(status, trace, messages) }
                    .collect { (status, trace, messages) ->
                        when (status) {
                            is TurnStatus.Running -> _state.update {
                                it.copy(isLoading = true, toolTrace = trace, error = null, canRetry = false, planAwaitingApproval = false)
                            }

                            is TurnStatus.Completed -> {
                                val arrived = status.insertedMessageId == null ||
                                    messages.any { it.id == status.insertedMessageId }
                                _state.update {
                                    it.copy(
                                        isLoading = !arrived,
                                        toolTrace = trace,
                                        error = status.error,
                                        canRetry = status.canRetry,
                                        planAwaitingApproval = status.planAwaitingApproval,
                                        lastResponseStats = ResponseStats(
                                            responseTimeMs = status.responseTimeMs,
                                            completionTokens = status.completionTokens,
                                            contextTokensUsed = status.contextTokensUsed,
                                        ),
                                    )
                                }
                                // Ответ доехал до ленты — чистая сессия больше не нужна (гасит бейдж
                                // «готово» в списке; markSeen сам не тронет ошибку/план-бар).
                                if (arrived && status.insertedMessageId != null) {
                                    turnManager.markSeen(convId)
                                }
                            }

                            is TurnStatus.Failed -> _state.update {
                                it.copy(isLoading = false, toolTrace = trace, error = status.message, canRetry = status.canRetry, planAwaitingApproval = false)
                            }
                        }
                    }
            }
        }
    }
```

- [ ] **Step 4: setConversationId, ensureConversation, observeMessages**

`setConversationId` — новое тело (ход больше НЕ отменяется при уходе с экрана; сбрасываются только подписки и экранное состояние):

```kotlin
    fun setConversationId(id: String) {
        if (_state.value.conversationId == id) return
        messagesJob?.cancel()
        sessionJob?.cancel()
        lessonBusy = false
        _state.value = ChatUiState.resetFor(id)
        convIdFlow.value = id
        // Новый чат (id пуст): диалога ещё нет, его создаст ensureConversation при отправке.
        if (id.isBlank()) return
        observeMessages(id)
        observeSession(id)
        // Вход в диалог гасит бейдж «готово» в списке (Running/ошибку/план-бар markSeen не трогает).
        turnManager.markSeen(id)
        viewModelScope.launch {
            // ... блок с getConversation/уроком БЕЗ ИЗМЕНЕНИЙ (isTutorial, lesson и т.д.) ...
        }
    }
```

(Блок урока внутри `viewModelScope.launch` остаётся дословно как был.)

В `ensureConversation` после `observeMessages(newId)` добавить две строки:

```kotlin
        observeSession(newId)
        convIdFlow.value = newId
```

В `observeMessages` убрать handoff-логику — тело `collect` становится:

```kotlin
                .collect { (messages, limit) ->
                    olderLoadInFlight = false
                    _state.update {
                        it.copy(messages = messages, hasMoreOlder = messages.size >= limit)
                    }
                }
```

(`pendingHandoffId`/`handoffArrived`/`resetStreaming`/`isLoading` оттуда удаляются — арбитраж теперь в `observeSession`, где messages участвуют в combine.)

- [ ] **Step 5: Делегаты интентов**

Хелпер:

```kotlin
    private fun currentConversationId(): String? =
        _state.value.conversationId?.takeIf { it.isNotBlank() }
```

`sendMessage`:

```kotlin
    private fun sendMessage() {
        val text = _state.value.inputText.trim()
        if (text.isEmpty()) return
        _state.update { it.copy(inputText = "") }
        viewModelScope.launch {
            try {
                // Маршрут "chat/new" даёт пустой id — создаём диалог ДО launchTurn: сессия ключуется по id.
                val convId = ensureConversation(text)
                turnManager.launchTurn(convId, text, _state.value.agentMode, planApproved = false, goalMaxIterations = _state.value.goalMaxIterations)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "send failed", e)
                _state.update { it.copy(error = e.message, canRetry = false) }
            }
        }
    }
```

`approvePlan`:

```kotlin
    /** План-мод: пользователь одобрил план → TurnManager запускает фазу выполнения. */
    private fun approvePlan() {
        val convId = currentConversationId() ?: return
        if (!_state.value.planAwaitingApproval) return
        turnManager.approvePlan(convId)
    }
```

`retry` и `cancel`:

```kotlin
    private fun retry() {
        currentConversationId()?.let { turnManager.retry(it) }
    }

    private fun cancel() {
        currentConversationId()?.let { turnManager.cancel(it) }
    }
```

В `handleIntent` поменять ветки:

```kotlin
            is ChatIntent.ClearError -> {
                currentConversationId()?.let { turnManager.clearError(it) }
                _state.update { it.copy(error = null, canRetry = false) }
            }
            // Смена режима инвалидирует «Повторить» прошлого хода (иначе ретрай уйдёт в старый режим).
            is ChatIntent.SetMode -> {
                currentConversationId()?.let { turnManager.invalidateRetry(it) }
                _state.update { it.copy(agentMode = intent.mode, planAwaitingApproval = false, canRetry = false) }
            }
            is ChatIntent.EditPlan -> currentConversationId()?.let { turnManager.dismissPlanApproval(it) }
```

`deleteMessage` — замена проверки `lastUserMessageId`:

```kotlin
    /** Удаляет сообщение. `state.messages` реактивен (Flow из БД), поэтому лента обновится сама. */
    private fun deleteMessage(messageId: String) {
        // Если это сообщение держит текущий «Повторить» — TurnManager погасит ретрай.
        currentConversationId()?.let { turnManager.invalidateRetryFor(it, messageId) }
        viewModelScope.launch {
            conversationRepository.deleteMessage(messageId)
        }
    }
```

- [ ] **Step 6: Сборка и полный прогон тестов**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Если компилятор ругается на неиспользуемые импорты/символы — дочистить по списку Step 1.

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, все существующие тесты зелёные (скриншотные Roborazzi-тесты не трогают турн-механику; если упал скриншотный — сравнить дифф, падение из-за этого рефакторинга недопустимо).

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/example/ui/chat/ChatViewModel.kt traceability.lock
git commit -m @'
refactor(chat): ChatViewModel — тонкий проектор поверх TurnManager

Ход агента больше не умирает с экраном: setConversationId/onCleared не отменяют
ход, стрим и статус берутся из сессии appScope-менеджера, «мигание» конца хода
решено деривацией (пузырь до прихода сообщения в окно ленты).

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 6: Бейджи ходов в списке диалогов

**Files:**
- Modify: `app/src/main/java/com/example/ui/conversations/ConversationsListState.kt`
- Modify: `app/src/main/java/com/example/ui/conversations/ConversationsListViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/conversations/ConversationsListScreen.kt`

**Interfaces:**
- Consumes: `turnManager.badges: Flow<Map<String, TurnStatus>>` (Task 3), `turnManager.cancel` (Task 2).
- Produces: `ConversationsListState.turnStatuses: Map<String, TurnStatus>`.

- [ ] **Step 1: Состояние**

`ConversationsListState.kt` — добавить импорт `com.aiopenpocket.agent.loop.TurnStatus` и поле:

```kotlin
  /** Статусы фоновых ходов по диалогам: «варится» / «готово» / «ошибка» (нет в мапе = хода нет). */
  val turnStatuses: Map<String, TurnStatus> = emptyMap(),
```

- [ ] **Step 2: ViewModel**

`ConversationsListViewModel.kt`:
- конструктор: добавить `private val turnManager: TurnManager` (импорты `com.aiopenpocket.agent.loop.TurnManager`);
- в `init` добавить второй коллектор:

```kotlin
    viewModelScope.launch {
      turnManager.badges.collect { statuses ->
        _state.update { it.copy(turnStatuses = statuses) }
      }
    }
```

- в `deleteConversation` первой строкой добавить `turnManager.cancel(id)` (удаление диалога с активным ходом — ход гасим до удаления строк БД).

- [ ] **Step 3: UI бейджа**

`ConversationsListScreen.kt`:

1. В вызове `ConversationItem` (строка ~138) добавить параметр:

```kotlin
                turnStatus = state.turnStatuses[conversation.id],
```

2. Сигнатура и тело `ConversationItem`: добавить параметр `turnStatus: TurnStatus?` (импорт `com.aiopenpocket.agent.loop.TurnStatus`, а также `androidx.compose.foundation.background`, `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.material3.CircularProgressIndicator`, `androidx.compose.ui.draw.clip`) и вставить индикатор между `Column(weight(1f))` и `IconButton(onToggleFavorite)`:

```kotlin
      // Индикация фонового хода: спиннер — агент думает, точка — готово (tertiary) или ошибка (error).
      when (turnStatus) {
        is TurnStatus.Running -> CircularProgressIndicator(
          modifier = Modifier.size(16.dp),
          strokeWidth = 2.dp,
        )

        is TurnStatus.Completed, is TurnStatus.Failed -> Box(
          modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(
              if (turnStatus is TurnStatus.Failed || (turnStatus is TurnStatus.Completed && turnStatus.error != null)) {
                MaterialTheme.colorScheme.error
              } else {
                MaterialTheme.colorScheme.tertiary
              },
            ),
        )

        null -> Unit
      }
```

(Если импортов `Box`/`Modifier.size` в файле ещё нет — добавить `androidx.compose.foundation.layout.Box`, `androidx.compose.foundation.layout.size`.)

- [ ] **Step 4: Сборка**

Run: `.\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL (все зелёные)

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/example/ui/conversations traceability.lock
git commit -m @'
feat(turns): бейджи фоновых ходов в списке диалогов

Спиннер «агент думает», точка «готово»/«ошибка» из turnManager.badges;
удаление диалога сначала гасит его активный ход.

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 7: Финальная верификация

**Files:**
- Modify: `docs/superpowers/specs/2026-07-04-background-turns-design.md` (строка статуса)

- [ ] **Step 1: Полный прогон**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 0 failed.

Run: `.\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Run: `traxer check`
Expected: 0 errors; допустимы только 2 известных чужих stale-warning'а по урокам. Если появился stale по `<TRACE_ID>` — подтвердить, что rationale верен, и `traxer bless <TRACE_ID>`.

- [ ] **Step 2: Обновить статус спеки**

В `docs/superpowers/specs/2026-07-04-background-turns-design.md` заменить строку
`Статус: одобрено на брейншторме, ждёт ревью спеки` на
`Статус: реализовано (ветка feature/background-turns)`.

```powershell
git add docs/superpowers/specs/2026-07-04-background-turns-design.md
git commit -m @'
docs: статус спеки фоновых ходов — реализовано

Trace: <TRACE_ID>

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

- [ ] **Step 3: Ручной смоук на устройстве (чек-лист для пользователя)**

Автотестов на это нет — проверяется руками на устройстве:

1. Отправить запрос → уйти на список диалогов → у чата спиннер → вернуться: стрим бежит, ответ доезжает без «мигания».
2. Отправить запрос → выйти из чата до конца хода → дождаться на списке точку «готово» → войти: ответ в истории, бейдж погас.
3. Два диалога: запустить ход в А, уйти в Б, запустить там — оба доработали.
4. Отправить запрос → свернуть приложение (Home) → дождаться пуша → тап по пушу открывает тот диалог.
5. Уронить ход (авиарежим) в фоне → пуш «Ход не удался» → войти → «Повторить» работает (и работает после выхода/возврата на экран — раньше пропадал).
6. План-мод: план → бар «Выполнить/Поправить» на месте, «Выполнить» запускает выполнение.
7. Stop-кнопка останавливает ход; удаление диалога с активным ходом не роняет приложение.

- [ ] **Step 4: Завершение ветки**

Инвокнуть скилл `superpowers:finishing-a-development-branch` (merge в `mvp` / PR / оставить — решает пользователь).
