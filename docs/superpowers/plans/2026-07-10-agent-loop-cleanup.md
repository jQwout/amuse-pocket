# Agent Loop Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Упростить слой логики чата и агентского цикла: убрать мёртвый код, собрать контракт режимов в чистые функции, вынести из TurnManager бизнес-постобработку и стрим-гейт, убрать Android-зависимости из `agent/loop` и `agent/core/prompt`, централизовать пользовательские строки, дедуплицировать обвязку оркестраторов.

**Architecture:** Поведение НЕ меняется — это рефакторинг с сохранением семантики. Контракт режимов становится парой чистых функций (`resolveTurnExecutionPlan` — «как выполнять ход», новая `resolveCompletedTurn` — «что значит результат хода»), обе с юнит-тестами. Android-зависимости уходят за порты (`AgentLog`, `AssetSource`, инъектируемые часы), провайдящиеся в DI-слое.

**Tech Stack:** Kotlin, kotlinx.coroutines (Flow/channelFlow), metro DI (`@Inject`/`@Provides`/`@ContributesTo(AppScope::class)`/`@BindingContainer`), JUnit4 + kotlinx-coroutines-test, Robolectric (только там, где реально нужен Android).

## Global Constraints

- Ветка: новая от `mvp` (например `refactor/agent-loop-cleanup`), НЕ `feature/glassmorphism` (там незакоммиченные правки уроков). Изоляция — через superpowers:using-git-worktrees, но с учётом памяти проекта: nested `.claude/worktrees/*` ломает сборку в Android Studio — предпочесть sibling-каталог, и проверять файловые операции через Bash (Read/Write в worktree однажды резолвились в основной чекаут).
- traxer (CLAUDE.md): перед каждым коммитом — `traxer check`; в каждом коммите трейлер `Trace: <feature-id>` (несколько строк, если фич несколько); новые файлы — с маркером `// @trace <id>`; stale-warnings blessить только после подтверждения rationale.
- Каждый коммит также заканчивается трейлером `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Тесты: `.\gradlew.bat :app:testDebugUnitTest` (Windows). Точечный запуск: `.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"`.
- Сборка: `.\gradlew.bat :app:compileDebugKotlin` — быстрый smoke после каждой правки.
- Стиль: комментарии в коде — на русском, в духе существующих (объясняют «почему», не «что»); пакеты — `com.aiopenpocket.*` при каталогах `com/example/*` (так во всём репо, НЕ чинить).
- Пользовательские тексты остаются русскими и байт-в-байт теми же — рефакторинг их только перемещает.

---

### Task 1: Удалить мёртвый PlanExecuteOrchestrator

`PlanExecuteOrchestrator` не подключён ни к одному входу: `TurnModule.provideTurnExecutor` диспатчит только REFLECT → `ReflectionOrchestrator`, всё остальное → `AgentLoopOrchestrator`. Grep по репо находит только упоминания в комментариях. Код сохраняется в git-истории и на ветке `feature/plan-execute` — ничего не теряем.

**Files:**
- Delete: `app/src/main/java/com/example/agent/loop/PlanExecuteOrchestrator.kt`
- Delete: `app/src/main/java/com/example/agent/loop/Plan.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt:25-30` (KDoc)
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt:23-32` (KDoc)
- Modify: `app/src/main/java/com/example/agent/loop/ReflectionOrchestrator.kt:23-39` (KDoc)
- Modify: `traceability.yaml:71-74` (rationale фичи `multi-agent-orchestration-27b8e3`)

**Interfaces:**
- Consumes: —
- Produces: — (удаление; ни один последующий таск не зависит от удалённых типов `Plan`, `PlanStep`, `PlanExecuteOrchestrator`)

- [ ] **Step 1: Убедиться, что удаляемое действительно мёртвое**

Run: `grep -rn "PlanExecuteOrchestrator\|PlanStep\|Plan(" app/src --include=*.kt | grep -v "agent/loop/PlanExecuteOrchestrator.kt" | grep -v "agent/loop/Plan.kt"`

Expected: только комментарии/KDoc в `ReactLoopRunner.kt`, `AgentLoopOrchestrator.kt`, `ReflectionOrchestrator.kt`, `Plan.kt` — ни одного вызова кода. Если найдётся живой вызов — СТОП, вернуться к пользователю.

- [ ] **Step 2: Удалить файлы**

```powershell
git rm app/src/main/java/com/example/agent/loop/PlanExecuteOrchestrator.kt app/src/main/java/com/example/agent/loop/Plan.kt
```

- [ ] **Step 3: Починить осиротевшие KDoc-ссылки**

В `ReactLoopRunner.kt` заменить KDoc класса (строки 25–30):

```kotlin
/**
 * The core ReAct loop (reason → act → observe). It streams [AgentEvent]s through [emit] and RETURNS
 * the final [AgentResult] instead of emitting a terminal Completed/Error — the caller decides what a
 * finished loop means (a whole chat reply, or one phase of a multi-phase orchestrator). Used by
 * [AgentLoopOrchestrator] for the plain chat turn and by [ReflectionOrchestrator] for draft/revise.
 */
```

В `AgentLoopOrchestrator.kt` заменить первый абзац KDoc (строки 23–27):

```kotlin
/**
 * Single-turn chat agent: assembles the prompt context, then runs the shared [ReactLoopRunner] over
 * the tool set and emits a terminal Completed/Error. The reasoning loop itself lives in
 * [ReactLoopRunner] so that multi-phase orchestrators ([ReflectionOrchestrator]) reuse the same engine.
 *
```

В `ReflectionOrchestrator.kt` заменить KDoc класса (строки 23–39):

```kotlin
/**
 * Reflection orchestrator — a teaching demo built ON TOP of the single [ReactLoopRunner], not a
 * separate engine. It runs three phases over one conversation:
 *
 *  1. **Draft** — the shared ReAct loop produces a first-pass answer (full tool set).
 *  2. **Critique** — one tool-less LLM call reads the draft and names concrete weaknesses.
 *  3. **Revise** — the shared ReAct loop rewrites the answer, addressing the critique.
 *
 * Everything streams as plain [AgentEvent.TextDelta] so the existing chat UI shows the whole
 * draft → critique → final process live, and the accumulated transcript is returned as
 * [AgentResult.text] so it persists as one assistant message. No new card, no new engine.
 *
 * Selected by [AgentMode.REFLECT]: the [com.aiopenpocket.di.TurnModule] TurnExecutor routes REFLECT
 * turns here instead of through [AgentLoopOrchestrator], because a 3-phase loop doesn't fit the
 * single-turn resolveTurnExecutionPlan contract.
 */
```

- [ ] **Step 4: Обновить rationale в traceability.yaml**

Заменить rationale фичи `multi-agent-orchestration-27b8e3` (строка 73) на:

```yaml
    rationale: "Демонстрация многоагентных схем. Живой путь: предметные мини-агенты-специалисты со своим скоупом инструментов (бюджет, языки, задачи, напоминания, нутрициология, дневник) — через roster в AgentsScreen. PlanExecuteOrchestrator (планировщик→исполнитель→синтезатор) удалён из mvp 2026-07-10 как неподключённый ни к одному входу; код остался в git-истории и на ветке feature/plan-execute."
```

- [ ] **Step 5: Сборка и тесты**

Run: `.\gradlew.bat :app:compileDebugKotlin` — Expected: BUILD SUCCESSFUL.
Run: `.\gradlew.bat :app:testDebugUnitTest` — Expected: BUILD SUCCESSFUL, все тесты зелёные.

- [ ] **Step 6: traxer check**

Run: `traxer check`
Expected: без broken link. Если фича `multi-agent-orchestration-27b8e3` даст stale-warning из-за правки rationale — `traxer bless multi-agent-orchestration-27b8e3` (rationale подтверждён этим же коммитом).

- [ ] **Step 7: Commit**

```powershell
git add -A
git commit -m @'
refactor(loop): удалить неподключённый PlanExecuteOrchestrator

Оркестратор не вызывался ни одним входом (TurnExecutor диспатчит только
REFLECT/остальное), код сохранён в истории и на ветке feature/plan-execute.

Trace: multi-agent-orchestration-27b8e3

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 2: Починить стейл-комментарии

**Files:**
- Modify: `app/src/main/java/com/example/ui/chat/ChatUiState.kt:44`

**Interfaces:**
- Consumes: —
- Produces: — (только комментарии)

- [ ] **Step 1: ChatUiState.agentId — убрать упоминание несуществующего supervisor**

Заменить строку 44:

```kotlin
  /** Bound mini-agent id; the coordinator routes through the supervisor, others scope the chat loop. */
```

на:

```kotlin
  /** Bound mini-agent id — scopes the chat loop's tool set and skill access (see PrePromptAssembler). */
```

- [ ] **Step 2: Проверить, что других упоминаний supervisor не осталось**

Run: `grep -rin "supervisor" app/src/main/java --include=*.kt`
Expected: пусто (KDoc ReflectionOrchestrator уже исправлен в Task 1; `AgentLoopOrchestrator.kt:51` упоминает «Supervisor/Plan-Execute orchestrators» в комментарии про error-контракт — заменить на «тот же error-контракт, что у ReflectionOrchestrator»):

В `AgentLoopOrchestrator.kt` строки 51–52 заменить:

```kotlin
    // Same error contract as the Supervisor/Plan-Execute orchestrators: a thrown error (assemble,
    // serialization, a DB write) becomes an in-band AgentEvent.Error instead of a raw flow throw.
```

на:

```kotlin
    // Тот же error-контракт, что у ReflectionOrchestrator: брошенное исключение (assemble,
    // сериализация, запись в БД) становится in-band AgentEvent.Error, а не raw throw из flow.
```

- [ ] **Step 3: Сборка**

Run: `.\gradlew.bat :app:compileDebugKotlin` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```powershell
git add -A
git commit -m @'
docs(chat): убрать стейл-комментарии про supervisor

Trace: streaming-llm-chat-6595a1

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 3: Гоал-бюджет — в resolveTurnExecutionPlan (systemPromptSuffix)

Сейчас GOAL-специфика размазана: чистая функция решает витки/тулзы/промпт-секцию, а текст «Бюджет: не более N витков…» приклеивается в оркестраторе. Переносим текст в `TurnExecutionPlan.systemPromptSuffix` — контракт режима собирается в одном файле и покрывается тем же тестом.

**Files:**
- Modify: `app/src/main/java/com/example/agent/loop/AgentMode.kt`
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt:57-65`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/AgentModeTest.kt`

**Interfaces:**
- Consumes: `resolveTurnExecutionPlan(mode, planApproved, goalMaxIterations, chatMaxIterations): TurnExecutionPlan` (существующая).
- Produces: `TurnExecutionPlan` получает новое поле `val systemPromptSuffix: String? = null`. Task 4 и далее ничего из этого не потребляют.

- [ ] **Step 1: Написать падающие тесты**

Добавить в `AgentModeTest.kt`:

```kotlin
  @Test
  fun goal_promptSuffixNamesTheIterationBudget() {
    val turn = plan(AgentMode.GOAL, goalMax = 3)
    assertTrue("суффикс должен называть бюджет витков", turn.systemPromptSuffix!!.contains("не более 3 витков"))
  }

  @Test
  fun nonGoalModes_haveNoPromptSuffix() {
    assertNull(plan(AgentMode.CHAT).systemPromptSuffix)
    assertNull(plan(AgentMode.PLAN, approved = false).systemPromptSuffix)
    assertNull(plan(AgentMode.PLAN, approved = true).systemPromptSuffix)
  }
```

И импорт: `import org.junit.Assert.assertNull`.

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.AgentModeTest"`
Expected: FAIL — `Unresolved reference: systemPromptSuffix` (ошибка компиляции тестов — это ожидаемый «красный»).

- [ ] **Step 3: Реализация в AgentMode.kt**

В `TurnExecutionPlan` добавить поле, в `resolveTurnExecutionPlan` — суффикс (текст скопирован из `AgentLoopOrchestrator.kt:61-62` байт-в-байт):

```kotlin
data class TurnExecutionPlan(
  val usesTools: Boolean,
  val maxIterations: Int,
  val promptMode: PromptMode,
  /** Доверенная добавка к системному промпту (сейчас — только бюджет витков гоал-мода). */
  val systemPromptSuffix: String? = null,
)
```

В конце `resolveTurnExecutionPlan` заменить `return TurnExecutionPlan(...)` на:

```kotlin
  // В гоал-моде сообщаем модели бюджет витков — иначе goal.md обещает «честно остановиться на
  // лимите», но модель не знает, какой виток последний, и обычно уходит в обрыв на tool_call.
  val suffix = if (mode == AgentMode.GOAL) {
    "Бюджет: не более " + maxIterations + " витков (рассуждение, действие, " +
      "самопроверка). На последнем витке дай финальный ответ или честный частичный итог — не начинай новых действий."
  } else {
    null
  }
  return TurnExecutionPlan(
    usesTools = !planning,
    maxIterations = maxIterations,
    promptMode = promptMode,
    systemPromptSuffix = suffix,
  )
```

- [ ] **Step 4: Использовать суффикс в AgentLoopOrchestrator**

Заменить строки 58–65 (`val systemContent = if (mode == AgentMode.GOAL) { … } else { … }`) на:

```kotlin
      val systemContent = turn.systemPromptSuffix
        ?.let { context.systemPrompt + "\n\n" + it }
        ?: context.systemPrompt
```

(Комментарий про goal.md переехал в `resolveTurnExecutionPlan` — здесь его удалить.)

- [ ] **Step 5: Тесты зелёные**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.AgentModeTest"`
Expected: PASS (все, включая новые).

- [ ] **Step 6: Полный прогон + traxer + commit**

Run: `.\gradlew.bat :app:testDebugUnitTest` — Expected: зелёный.
Run: `traxer check` — разгрести, если что показал.

```powershell
git add -A
git commit -m @'
refactor(modes): гоал-бюджет промпта — в resolveTurnExecutionPlan

Текст бюджета витков переезжает из оркестратора в TurnExecutionPlan
(systemPromptSuffix): контракт режима читается и тестируется в одном месте.

Trace: rezhimy-agenta-plan-i-goal-ee2361

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 4: Чистая политика завершения хода (resolveCompletedTurn) + все пользовательские тексты в TurnTexts

Бизнес-постобработка завершённого хода (`TurnManager.handleCompleted`: извлечение render_card, NEED_INFO, blank-ошибки, план-бар) и GOAL-фолбэк пустого ответа (из `AgentLoopOrchestrator`) становятся ОДНОЙ чистой функцией рядом с `AgentMode` — симметрично `resolveTurnExecutionPlan`. Все русские строки слоя ходов собираются в `TurnTexts`.

**Files:**
- Create: `app/src/main/java/com/example/agent/loop/TurnCompletion.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnManager.kt` (handleCompleted, approvePlan, notifyIfBackground-preview, companion)
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt:90-100` (убрать GOAL-фолбэк)
- Modify: `app/src/main/java/com/example/agent/loop/AgentMode.kt` (KDoc-указатель на второй полюс контракта)
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/TurnCompletionTest.kt`

**Interfaces:**
- Consumes: `AgentMode`, `AgentResult(text, toolTrace, state, iterations, errorMessage, promptTokensEstimate, completionTokensEstimate)`, `ToolTrace(iteration, toolName, arguments, result)`, `ToolResult.Success(data)` — все существующие.
- Produces (потребляет TurnManager в этом же таске):

```kotlin
object TurnTexts // константы-строки, см. Step 3
const val NEED_INFO_MARKER = "[[NEED_INFO]]"

data class CompletedTurn(
  val responseText: String,
  val cardSpecs: List<String>,
  val blankError: String?,
  val canRetry: Boolean,
  val planAwaitingApproval: Boolean,
)

fun resolveCompletedTurn(
  mode: AgentMode,
  planApproved: Boolean,
  result: AgentResult,
  hasRetryContext: Boolean,
): CompletedTurn
```

- [ ] **Step 1: Написать падающие тесты**

Создать `app/src/test/java/com/aiopenpocket/agent/loop/TurnCompletionTest.kt`:

```kotlin
package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.mcp.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Контракт результата хода: NEED_INFO, план-бар, blank-ошибки, GOAL-фолбэк, render_card. */
class TurnCompletionTest {

  private fun result(text: String, trace: List<ToolTrace> = emptyList()) =
    AgentResult(text = text, toolTrace = trace, state = AgentState.FINISHED, iterations = 1)

  private fun card(spec: String) =
    ToolTrace(iteration = 1, toolName = "render_card", arguments = emptyMap(), result = ToolResult.Success(spec))

  private fun resolve(
    mode: AgentMode = AgentMode.CHAT,
    planApproved: Boolean = false,
    res: AgentResult,
    hasRetryContext: Boolean = true,
  ) = resolveCompletedTurn(mode, planApproved, res, hasRetryContext)

  @Test
  fun `обычный ответ проходит как есть`() {
    val out = resolve(res = result("Привет!"))
    assertEquals("Привет!", out.responseText)
    assertNull(out.blankError)
    assertFalse(out.canRetry)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `render_card извлекаются в cardSpecs по порядку`() {
    val out = resolve(res = result("текст", listOf(card("{a}"), card("{b}"))))
    assertEquals(listOf("{a}", "{b}"), out.cardSpecs)
  }

  @Test
  fun `card-only ответ — не пустой, ошибки нет`() {
    val out = resolve(res = result("", listOf(card("{a}"))))
    assertNull(out.blankError)
    assertFalse(out.canRetry)
  }

  @Test
  fun `пустой ответ в чате — blank-ошибка, ретрай при наличии контекста`() {
    val out = resolve(res = result(""))
    assertEquals(TurnTexts.BLANK_REPLY_ERROR, out.blankError)
    assertTrue(out.canRetry)
    assertFalse(resolve(res = result(""), hasRetryContext = false).canRetry)
  }

  @Test
  fun `план-фаза - непустой план поднимает план-бар и триммится`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("  1. шаг\n"))
    assertEquals("1. шаг", out.responseText)
    assertTrue(out.planAwaitingApproval)
  }

  @Test
  fun `план-фаза - NEED_INFO вырезается и НЕ поднимает план-бар`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("[[NEED_INFO]] Какой отчёт нужен?"))
    assertEquals("Какой отчёт нужен?", out.responseText)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `план-фаза - NEED_INFO с пустым остатком даёт фолбэк-вопрос`() {
    val out = resolve(mode = AgentMode.PLAN, res = result("[[NEED_INFO]]"))
    assertEquals(TurnTexts.NEED_INFO_FALLBACK_QUESTION, out.responseText)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `план-фаза - пустой ответ даёт план-ошибку`() {
    val out = resolve(mode = AgentMode.PLAN, res = result(""))
    assertEquals(TurnTexts.BLANK_PLAN_ERROR, out.blankError)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `одобренный план обрабатывается как обычный чат`() {
    val out = resolve(mode = AgentMode.PLAN, planApproved = true, res = result("[[NEED_INFO]] х"))
    // После одобрения маркер не интерпретируется — фаза планирования закончилась.
    assertEquals("[[NEED_INFO]] х", out.responseText)
    assertFalse(out.planAwaitingApproval)
  }

  @Test
  fun `гоал-мод - пустой ответ на лимите заменяется честным частичным итогом`() {
    val out = resolve(mode = AgentMode.GOAL, res = result(""))
    assertEquals(TurnTexts.GOAL_ITERATION_LIMIT_PARTIAL, out.responseText)
    assertNull("непустой фолбэк — это не blank-ошибка", out.blankError)
  }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnCompletionTest"`
Expected: FAIL — `Unresolved reference: resolveCompletedTurn`.

- [ ] **Step 3: Реализация — TurnCompletion.kt**

Создать `app/src/main/java/com/example/agent/loop/TurnCompletion.kt` (тексты скопированы байт-в-байт из TurnManager.kt:294-334 и AgentLoopOrchestrator.kt:92):

```kotlin
package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361

import com.aiopenpocket.agent.core.mcp.ToolResult

/**
 * Все пользовательские тексты слоя ходов — единственное место (задел под i18n: UI-слой сможет
 * подменить их ресурсами, не трогая логику). Логика на них только ССЫЛАЕТСЯ, не порождает свои.
 */
object TurnTexts {
  /** Гоал-мод упёрся в лимит витков с пустым текстом — честный частичный итог вместо пустоты. */
  const val GOAL_ITERATION_LIMIT_PARTIAL =
    "Достиг лимита витков — задача выполнена частично. Подробности — в трассировке инструментов."

  /** NEED_INFO-ответ, из которого после вырезания маркера ничего не осталось. */
  const val NEED_INFO_FALLBACK_QUESTION = "Уточни, пожалуйста, детали задачи — что именно нужно сделать?"

  const val BLANK_PLAN_ERROR = "Модель не вернула план — попробуй ещё раз."
  const val BLANK_REPLY_ERROR = "Модель вернула пустой ответ — попробуй ещё раз."

  /** Реплика, которой ход одобрения плана попадает в историю. */
  const val APPROVE_PLAN_MESSAGE = "Одобряю план, выполни его по шагам."

  /** Превью пуша, когда ход завершился без текста (card-only ответ). */
  const val TURN_DONE_FALLBACK = "Готово"
}

/** Маркер план-планирования: модель ставит его в начало ответа-вопроса (вырезается из текста). */
const val NEED_INFO_MARKER = "[[NEED_INFO]]"

private const val RENDER_CARD_TOOL = "render_card"

/**
 * Что означает завершившийся ход. Второй полюс контракта режимов: [resolveTurnExecutionPlan]
 * решает, КАК выполнять ход, эта структура — ЧТО значит его результат.
 */
data class CompletedTurn(
  /** Текст ассистентского сообщения для записи в БД (может быть пуст при card-only ответе). */
  val responseText: String,
  /** render_card-спеки из трейса — станут инлайн-карточками чата (первая — вместе с текстом). */
  val cardSpecs: List<String>,
  /** Ошибка «пустой ответ» для баннера (иначе null). */
  val blankError: String?,
  val canRetry: Boolean,
  /** План-мод: показан непустой план, ждём «Выполнить»/«Поправить». */
  val planAwaitingApproval: Boolean,
)

/**
 * Чистое решение по результату хода (симметрично [resolveTurnExecutionPlan], покрыто
 * TurnCompletionTest): NEED_INFO-гейт план-мода, blank-ошибки, GOAL-фолбэк пустого ответа,
 * извлечение render_card. Никакого I/O — TurnManager лишь применяет решение.
 */
fun resolveCompletedTurn(
  mode: AgentMode,
  planApproved: Boolean,
  result: AgentResult,
  /** Есть ли сохранённая реплика пользователя для «Повторить» (см. TurnSession.lastUserText). */
  hasRetryContext: Boolean,
): CompletedTurn {
  val cardSpecs = result.toolTrace
    .filter { it.toolName == RENDER_CARD_TOOL }
    .mapNotNull { (it.result as? ToolResult.Success)?.data }

  // Гоал-мод: на лимите витков не отдаём пустой ответ — честный частичный итог (трейс есть в UI).
  val rawText = if (mode == AgentMode.GOAL && result.text.isBlank()) {
    TurnTexts.GOAL_ITERATION_LIMIT_PARTIAL
  } else {
    result.text
  }

  // Фаза планирования: маркер [[NEED_INFO]] В НАЧАЛЕ ответа = уточняющий вопрос, не план.
  val planningPhase = mode == AgentMode.PLAN && !planApproved
  val needInfo = planningPhase && rawText.trimStart().startsWith(NEED_INFO_MARKER)
  val responseText = when {
    needInfo -> rawText.trimStart().removePrefix(NEED_INFO_MARKER).trim()
      .ifBlank { TurnTexts.NEED_INFO_FALLBACK_QUESTION }

    planningPhase -> rawText.trim()
    else -> rawText
  }

  val blankReply = responseText.isBlank() && cardSpecs.isEmpty()
  val blankError = when {
    planningPhase && blankReply -> TurnTexts.BLANK_PLAN_ERROR
    blankReply -> TurnTexts.BLANK_REPLY_ERROR
    else -> null
  }

  return CompletedTurn(
    responseText = responseText,
    cardSpecs = cardSpecs,
    blankError = blankError,
    canRetry = blankReply && hasRetryContext,
    planAwaitingApproval = planningPhase && !needInfo && responseText.isNotBlank(),
  )
}
```

- [ ] **Step 4: Тесты политики зелёные**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.TurnCompletionTest"`
Expected: PASS.

- [ ] **Step 5: Переключить TurnManager на политику**

В `TurnManager.kt` заменить тело `handleCompleted` (строки 282–335) на:

```kotlin
  private suspend fun handleCompleted(
    session: TurnSession,
    mode: AgentMode,
    planApproved: Boolean,
    result: AgentResult,
  ) {
    val convId = session.conversationId
    // Все решения (NEED_INFO, blank-ошибки, GOAL-фолбэк, карточки) — в чистой политике; здесь
    // только применяем: записать, обновить статус, уведомить.
    val outcome = resolveCompletedTurn(mode, planApproved, result, hasRetryContext = session.lastUserText != null)
    val insertedId = if (outcome.responseText.isNotBlank() || outcome.cardSpecs.isNotEmpty()) {
      persistence.addAssistantMessage(convId, outcome.responseText, outcome.cardSpecs.firstOrNull())
    } else {
      null
    }
    outcome.cardSpecs.drop(1).forEach { spec ->
      persistence.addAssistantMessage(convId, "", spec)
    }

    session.toolTraceFlow.value = result.toolTrace
    // streamingText НЕ сбрасываем: экран держит пузырь, пока сообщение не приедет в окно ленты
    // (замена pendingHandoffId — «мигание» конца хода не возвращается).
    session.statusFlow.value = TurnStatus.Completed(
      insertedMessageId = insertedId,
      responseTimeMs = System.currentTimeMillis() - session.responseStartTime,
      completionTokens = result.completionTokensEstimate,
      contextTokensUsed = result.promptTokensEstimate,
      error = outcome.blankError,
      canRetry = outcome.canRetry,
      planAwaitingApproval = outcome.planAwaitingApproval,
    )
    notifyIfBackground(convId, preview = outcome.responseText.take(140).ifBlank { TurnTexts.TURN_DONE_FALLBACK }, failed = false)
  }
```

Из companion `TurnManager` удалить `NEED_INFO_MARKER` и `APPROVE_PLAN_MESSAGE`; в `approvePlan` заменить `APPROVE_PLAN_MESSAGE` на `TurnTexts.APPROVE_PLAN_MESSAGE`.

- [ ] **Step 6: Убрать GOAL-фолбэк из AgentLoopOrchestrator**

Заменить строки 88–100 (блок с `promptTokensEstimate` … `result = baseResult.copy(...)`) на:

```kotlin
      // Token estimates feed the chat's response stats bar; computed at the boundary so the
      // ReactLoopRunner stays unaware of UI concerns. GOAL-фолбэк пустого текста теперь в
      // resolveCompletedTurn (TurnManager) — оценка токенов на «пустом» ходе честно даёт 0.
      val promptTokensEstimate = LlmContextConfig.estimateTokens(context.systemPrompt) +
        context.history.sumOf { LlmContextConfig.estimateTokens(it.content) }
      val result = baseResult.copy(
        promptTokensEstimate = promptTokensEstimate,
        completionTokensEstimate = LlmContextConfig.estimateTokens(baseResult.text),
      )
```

(Поведенческая дельта — только строка статистики «токены ответа» на GOAL-лимитном ходе: 0 вместо оценки фолбэк-текста. Сообщение в БД и на экране идентично прежнему.)

- [ ] **Step 7: KDoc-указатель контракта режимов**

В `AgentMode.kt` дополнить KDoc enum (после строки про REFLECT):

```kotlin
 *
 * Контракт режима целиком: [resolveTurnExecutionPlan] (как выполнять ход) +
 * [resolveCompletedTurn] (что значит его результат); REFLECT диспатчится на свой оркестратор
 * в TurnModule.provideTurnExecutor.
```

- [ ] **Step 8: Полный прогон**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: PASS, включая существующий `TurnManagerTest` (его сценарии NEED_INFO/blank/plan-бар должны пройти без правок — политика повторяет прежнее поведение). Если `TurnManagerTest` упал — чинить РЕАЛИЗАЦИЮ до совпадения со старым поведением, не тест.

- [ ] **Step 9: traxer + commit**

Run: `traxer check`

```powershell
git add -A
git commit -m @'
refactor(loop): чистая политика завершения хода + TurnTexts

resolveCompletedTurn (NEED_INFO, blank-ошибки, GOAL-фолбэк, render_card) —
чистая функция с тестами, симметрична resolveTurnExecutionPlan.
Все пользовательские строки слоя ходов собраны в TurnTexts.

Trace: rezhimy-agenta-plan-i-goal-ee2361
Trace: fonovye-hody-agenta-dde42d

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 5: StreamingTextBuffer — стрим-гейт из TurnManager в тестируемый класс

UI-механика стрима (StringBuilder + гейт ~20 публикаций/сек) уходит из TurnManager в маленький класс с инъектируемыми часами. Заодно из TurnManager исчезает `android.os.SystemClock` (часть Task 6).

**Files:**
- Create: `app/src/main/java/com/example/agent/loop/StreamingTextBuffer.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnSession.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnManager.kt` (конструктор + collectTurnEvents + resetStreaming)
- Modify: `app/src/main/java/com/example/di/TurnModule.kt` (передать часы)
- Modify: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt` (конструктор + фейковые часы)
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/StreamingTextBufferTest.kt`

**Interfaces:**
- Consumes: —
- Produces (потребляют TurnSession/TurnManager в этом же таске; Task 6 опирается на то, что SystemClock из TurnManager уже удалён):

```kotlin
class StreamingTextBuffer(nowMs: () -> Long, minPublishIntervalMs: Long = 50L) {
  fun append(delta: String): String?  // снапшот к публикации или null (гейт)
  fun flush(): String?                // хвост, если есть что публиковать
  fun reset()
}
// TurnManager получает новый параметр конструктора: monotonicNowMs: () -> Long
// TurnSession конструируется как TurnSession(conversationId, monotonicNowMs)
```

- [ ] **Step 1: Написать падающие тесты**

Создать `app/src/test/java/com/aiopenpocket/agent/loop/StreamingTextBufferTest.kt`:

```kotlin
package com.aiopenpocket.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Гейт частоты публикаций стрим-текста (~20/сек): копим всё, публикуем не чаще интервала. */
class StreamingTextBufferTest {

  private var now = 100_000L
  private val buffer = StreamingTextBuffer(nowMs = { now }, minPublishIntervalMs = 50L)

  @Test
  fun `первая дельта публикуется сразу`() {
    assertEquals("a", buffer.append("a"))
  }

  @Test
  fun `дельты внутри интервала копятся молча, следующая публикация отдаёт всё накопленное`() {
    buffer.append("a")
    now += 10
    assertNull(buffer.append("b"))
    now += 50
    assertEquals("abc", buffer.append("c"))
  }

  @Test
  fun `flush отдаёт хвост, не прошедший гейт`() {
    buffer.append("a")
    now += 10
    buffer.append("b")
    assertEquals("ab", buffer.flush())
  }

  @Test
  fun `flush пустого буфера — null`() {
    assertNull(buffer.flush())
  }

  @Test
  fun `reset очищает буфер и гейт`() {
    buffer.append("a")
    buffer.reset()
    assertNull(buffer.flush())
    assertEquals("b", buffer.append("b"))
  }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.StreamingTextBufferTest"`
Expected: FAIL — `Unresolved reference: StreamingTextBuffer`.

- [ ] **Step 3: Реализация StreamingTextBuffer.kt**

```kotlin
package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d

/**
 * Накопитель стрим-текста с гейтом частоты публикаций: toString() копирует весь накопленный текст,
 * чаще ~20 раз/сек глазу не нужно. Часы инъектируются (монотонные, см. TurnModule) — класс чистый
 * и тестируется без Android.
 */
class StreamingTextBuffer(
  private val nowMs: () -> Long,
  private val minPublishIntervalMs: Long = DEFAULT_PUBLISH_INTERVAL_MS,
) {
  private val builder = StringBuilder()
  private var lastPublishAt = 0L

  /** Копит [delta]; возвращает снапшот для публикации, если гейт пропускает, иначе null. */
  fun append(delta: String): String? {
    builder.append(delta)
    val now = nowMs()
    if (now - lastPublishAt < minPublishIntervalMs) return null
    lastPublishAt = now
    return builder.toString()
  }

  /** Хвост стрима, не прошедший гейт: снапшот, если есть накопленное, иначе null. */
  fun flush(): String? = if (builder.isNotEmpty()) builder.toString() else null

  fun reset() {
    builder.setLength(0)
    lastPublishAt = 0L
  }

  companion object {
    /** Минимальный интервал публикации стрим-текста (~20 обновлений/сек). */
    const val DEFAULT_PUBLISH_INTERVAL_MS = 50L
  }
}
```

- [ ] **Step 4: Тесты буфера зелёные**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.loop.StreamingTextBufferTest"`
Expected: PASS.

- [ ] **Step 5: Переключить TurnSession и TurnManager**

`TurnSession.kt` — конструктор и поля стрима:

```kotlin
class TurnSession internal constructor(
  val conversationId: String,
  monotonicNowMs: () -> Long,
) {
```

и заменить

```kotlin
  internal val streamingBuilder = StringBuilder()
  internal var lastStreamPublishAt = 0L
```

на

```kotlin
  internal val streamBuffer = StreamingTextBuffer(monotonicNowMs)
```

`TurnManager.kt`:
1. Конструктор — новый параметр:

```kotlin
class TurnManager(
  private val executor: TurnExecutor,
  private val persistence: TurnPersistence,
  private val foreground: ForegroundState,
  private val notifier: TurnNotifier,
  private val scope: CoroutineScope,
  /** Монотонные часы для гейта стрима (SystemClock.uptimeMillis в проде, фейк в тестах). */
  private val monotonicNowMs: () -> Long,
) {
```

2. В `startTurn` создание сессии: `TurnSession(conversationId, monotonicNowMs)`.
3. В `collectTurnEvents` ветка TextDelta:

```kotlin
        is AgentEvent.TextDelta ->
          session.streamBuffer.append(event.content)?.let { session.streamingTextFlow.value = it }
```

4. Ветка StateChanged:

```kotlin
        is AgentEvent.StateChanged -> when (event.state) {
          // Новый виток ReAct стримит свой текст заново — показываем текст ТЕКУЩЕГО витка.
          AgentState.STREAMING -> resetStreaming(session)
          // Стрим витка закончился: дофлашиваем хвост, не прошедший гейт частоты.
          else -> session.streamBuffer.flush()?.let { session.streamingTextFlow.value = it }
        }
```

5. `resetStreaming`:

```kotlin
  private fun resetStreaming(session: TurnSession) {
    session.streamBuffer.reset()
    session.streamingTextFlow.value = ""
  }
```

6. Удалить `import android.os.SystemClock` и константу `STREAM_PUBLISH_INTERVAL_MS` из companion.

`TurnModule.kt` — провайдер менеджера:

```kotlin
  ): TurnManager = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = foreground,
    notifier = notifier,
    // Свой скоуп уровня приложения: ход агента живёт, пока жив процесс, а не экран.
    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    monotonicNowMs = android.os.SystemClock::uptimeMillis,
  )
```

- [ ] **Step 6: Обновить TurnManagerTest**

В хелпере `manager()` добавить параметр часов (фиксированное «большое» время — первая публикация проходит гейт, как и в проде):

```kotlin
  private fun TestScope.manager() = TurnManager(
    executor = executor,
    persistence = persistence,
    foreground = { inForeground },
    notifier = notifier,
    scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
    monotonicNowMs = { 100_000L },
  )
```

- [ ] **Step 7: Полный прогон + traxer + commit**

Run: `.\gradlew.bat :app:testDebugUnitTest` — Expected: PASS (в т.ч. тест «TextDelta копится, STREAMING сбрасывает буфер»).
Run: `traxer check`

```powershell
git add -A
git commit -m @'
refactor(loop): стрим-гейт из TurnManager в StreamingTextBuffer

Механика буфера и частоты публикаций — отдельный чистый класс с
инъектируемыми часами (и тестами); из TurnManager уходит SystemClock.

Trace: fonovye-hody-agenta-dde42d

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 6: Порты AgentLog и AssetSource — Android уходит из agent/loop и agent/core/prompt

После Task 5 в домене остаются `android.util.Log` (AgentLoopOrchestrator, ReactLoopRunner, TurnManager, ReflectionOrchestrator) и `android.app.Application` (PrePromptAssembler). Заводим два порта; реализации — в DI-слое. `TurnManagerTest` слезает с Robolectric.

**Files:**
- Create: `app/src/main/java/com/example/agent/core/AgentLog.kt`
- Create: `app/src/main/java/com/example/agent/core/prompt/AssetSource.kt`
- Create: `app/src/main/java/com/example/di/PlatformModule.kt`
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReflectionOrchestrator.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnManager.kt`
- Modify: `app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt`
- Modify: `app/src/main/java/com/example/di/TurnModule.kt`
- Modify: `app/src/test/java/com/aiopenpocket/agent/loop/TurnManagerTest.kt`

**Interfaces:**
- Consumes: metro DI (`@Provides`, `@ContributesTo(AppScope::class)`, `@BindingContainer`, `@SingleIn(AppScope::class)`); `Application` уже провайдится в `AppGraph.create(@Provides application: Application)`.
- Produces (потребляет Task 7 — `AgentLog` в сигнатуре общего скелета):

```kotlin
// com.aiopenpocket.agent.core
interface AgentLog {
  fun d(tag: String, message: String)
  fun w(tag: String, message: String)
  fun e(tag: String, message: String, error: Throwable? = null)
}

// com.aiopenpocket.agent.core.prompt
fun interface AssetSource {
  /** Текст ассета по пути внутри assets/, либо null (нет файла / ошибка чтения). */
  fun load(path: String): String?
}
```

- [ ] **Step 1: Создать порты**

`app/src/main/java/com/example/agent/core/AgentLog.kt`:

```kotlin
package com.aiopenpocket.agent.core

// @trace streaming-llm-chat-6595a1

/**
 * Порт логирования для доменного слоя (agent/loop, agent/core/prompt): те же уровни, что у
 * android.util.Log, но без зависимости от Android — доменные тесты остаются чистыми JVM-юнитами.
 * Реализация — в di/PlatformModule.
 */
interface AgentLog {
  fun d(tag: String, message: String)
  fun w(tag: String, message: String)
  fun e(tag: String, message: String, error: Throwable? = null)
}
```

`app/src/main/java/com/example/agent/core/prompt/AssetSource.kt`:

```kotlin
package com.aiopenpocket.agent.core.prompt

// @trace rezhimy-agenta-plan-i-goal-ee2361

/**
 * Порт чтения текстовых ассетов (prompts/, personas/) для сборки промпта: PrePromptAssembler не
 * должен тянуть android.app.Application ради AssetManager. Реализация — в di/PlatformModule.
 */
fun interface AssetSource {
  /** Текст ассета по пути внутри assets/, либо null (нет файла / ошибка чтения). */
  fun load(path: String): String?
}
```

- [ ] **Step 2: Провайдеры в DI**

`app/src/main/java/com/example/di/PlatformModule.kt`:

```kotlin
package com.aiopenpocket.di

// @trace streaming-llm-chat-6595a1

import android.app.Application
import android.util.Log
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.prompt.AssetSource
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/** Android-реализации портов доменного слоя (лог, ассеты). */
@ContributesTo(AppScope::class)
@BindingContainer
object PlatformModule {

  @Provides
  @SingleIn(AppScope::class)
  fun provideAgentLog(): AgentLog = object : AgentLog {
    override fun d(tag: String, message: String) {
      Log.d(tag, message)
    }

    override fun w(tag: String, message: String) {
      Log.w(tag, message)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
      Log.e(tag, message, error)
    }
  }

  @Provides
  @SingleIn(AppScope::class)
  fun provideAssetSource(application: Application): AssetSource = AssetSource { path ->
    runCatching { application.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
  }
}
```

- [ ] **Step 3: PrePromptAssembler — Application → AssetSource**

В `PrePromptAssembler.kt`:
1. Конструктор: заменить `private val application: Application` на `private val assets: AssetSource`; удалить `import android.app.Application`.
2. Заменить приватный метод `loadAsset`:

```kotlin
  private fun loadAsset(path: String): String? = assets.load(path)
```

(Все вызовы `loadAsset(...)` остаются как были.)

- [ ] **Step 4: AgentLog вместо android.util.Log в четырёх классах**

Во всех четырёх — удалить `import android.util.Log`, добавить `import com.aiopenpocket.agent.core.AgentLog` и параметр конструктора `private val log: AgentLog`, заменить вызовы `Log.d(TAG, …)` → `log.d(TAG, …)`, `Log.w` → `log.w`, `Log.e(TAG, msg, e)` → `log.e(TAG, msg, e)`:

1. `AgentLoopOrchestrator.kt` — `@Inject`-конструктор, metro подхватит сам.
2. `ReactLoopRunner.kt` — то же.
3. `ReflectionOrchestrator.kt` — то же; там `android.util.Log.d(...)` вызывается с полным именем — заменить на `log.d(...)`.
4. `TurnManager.kt` — конструктор без `@Inject` (собирается в TurnModule): добавить параметр `private val log: AgentLog`, а в `TurnModule.provideTurnManager` добавить параметр `log: AgentLog` и передать `log = log`.

- [ ] **Step 5: TurnManagerTest — слезть с Robolectric**

В `TurnManagerTest.kt`:
1. Удалить `@RunWith(RobolectricTestRunner::class)` и его импорты (`org.junit.runner.RunWith`, `org.robolectric.RobolectricTestRunner`).
2. Обновить KDoc класса: `/** TurnManager тестируется на скриптовых фейках портов: без LLM, без Room, без Android. */`
3. В хелпер `manager()` добавить no-op лог:

```kotlin
  private object NoopLog : com.aiopenpocket.agent.core.AgentLog {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, error: Throwable?) {}
  }
```

и параметр `log = NoopLog` в конструкторе `TurnManager`.

- [ ] **Step 6: Проверить, что Android из домена ушёл**

Run: `grep -rn "import android" app/src/main/java/com/example/agent/loop app/src/main/java/com/example/agent/core/prompt`
Expected: пусто.

- [ ] **Step 7: Полный прогон + traxer + commit**

Run: `.\gradlew.bat :app:testDebugUnitTest` — Expected: PASS (TurnManagerTest теперь без Robolectric — заметно быстрее; PrePromptAssemblerModeAssetsTest не трогаем: он проверяет сами ассеты через Robolectric и PrePromptAssembler не конструирует).
Run: `traxer check`

```powershell
git add -A
git commit -m @'
refactor(core): порты AgentLog и AssetSource — Android уходит из домена

agent/loop и agent/core/prompt больше не импортируют android.*:
лог и чтение ассетов — за портами, реализации в di/PlatformModule.
TurnManagerTest слезает с Robolectric.

Trace: streaming-llm-chat-6595a1
Trace: rezhimy-agenta-plan-i-goal-ee2361
Trace: fonovye-hody-agenta-dde42d

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

### Task 7: Общий скелет agentTurnFlow для оркестраторов

`AgentLoopOrchestrator.execute` и `ReflectionOrchestrator.execute` повторяют один скелет: `channelFlow { try … rethrow CancellationException … in-band Error } .buffer(UNLIMITED).flowOn(IO)`. Выносим в одну функцию — третий оркестратор (если появится) получит контракт бесплатно.

**Files:**
- Create: `app/src/main/java/com/example/agent/loop/AgentTurnFlow.kt`
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReflectionOrchestrator.kt`

**Interfaces:**
- Consumes: `AgentLog` (Task 6), `AgentEvent`/`AgentState` (существующие).
- Produces:

```kotlin
internal fun agentTurnFlow(
  log: AgentLog,
  tag: String,
  defaultErrorMessage: String,
  body: suspend ProducerScope<AgentEvent>.() -> Unit,
): Flow<AgentEvent>
```

- [ ] **Step 1: Реализация AgentTurnFlow.kt**

```kotlin
package com.aiopenpocket.agent.loop

// @trace streaming-llm-chat-6595a1

import com.aiopenpocket.agent.core.AgentLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn

/**
 * Общий скелет хода оркестратора — единый error-контракт для всех: брошенное исключение (assemble,
 * сериализация, запись в БД) становится in-band [AgentEvent.Error], а не raw throw из flow;
 * отмена (Stop / новый ход) — штатный путь и пробрасывается для cancelAndJoin. Буфер безлимитный,
 * работа — на IO.
 */
internal fun agentTurnFlow(
  log: AgentLog,
  tag: String,
  defaultErrorMessage: String,
  body: suspend ProducerScope<AgentEvent>.() -> Unit,
): Flow<AgentEvent> = channelFlow {
  try {
    body()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    log.e(tag, "✗ turn failed", e)
    send(AgentEvent.StateChanged(AgentState.ERROR))
    send(AgentEvent.Error(e.message ?: defaultErrorMessage))
  }
}.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)
```

- [ ] **Step 2: Переключить AgentLoopOrchestrator**

`execute` начинается так (вместо `channelFlow { try {`):

```kotlin
  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider = LlmProvider.NIM,
    mode: AgentMode = AgentMode.CHAT,
    planApproved: Boolean = false,
    goalMaxIterations: Int = 3,
  ): Flow<AgentEvent> = agentTurnFlow(log, TAG, "Unknown error") {
```

Тело — прежнее содержимое `try { … }` без изменений (включая финальный `runCatching { conversationAnnotator.annotate(conversationId) }`), а закрывается:

```kotlin
  }
```

Удалить: собственные `catch`-ветки, `.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)` и ставшие ненужными импорты (`CancellationException`, `Dispatchers`, `Channel`, `buffer`, `channelFlow`, `flowOn`).

- [ ] **Step 3: Переключить ReflectionOrchestrator**

Аналогично:

```kotlin
  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider = LlmProvider.NIM,
  ): Flow<AgentEvent> = agentTurnFlow(log, TAG, "Reflection failed") {
```

Внутри остаются transcript-tap, `emitError` (для НЕброшенных ошибок фаз — state=ERROR от ReactLoopRunner) и весь порядок фаз; `runCritic` по-прежнему кидает `IllegalStateException` при сбое стрима критики — теперь его ловит общий скелет с тем же результатом. Удалить собственные `catch`-ветки, `.buffer(...).flowOn(...)` и лишние импорты.

ВАЖНО (метки return): два места `return@channelFlow emitError(...)` (после draft и после revise) перестанут компилироваться — метка `channelFlow` теперь внутри `agentTurnFlow`. Заменить обе на:

```kotlin
      if (draft.state == AgentState.ERROR) {
        return@agentTurnFlow emitError(draft.errorMessage)
      }
```

```kotlin
      if (revised.state == AgentState.ERROR) {
        return@agentTurnFlow emitError(revised.errorMessage)
      }
```

- [ ] **Step 4: Сборка и полный прогон**

Run: `.\gradlew.bat :app:compileDebugKotlin` — Expected: BUILD SUCCESSFUL.
Run: `.\gradlew.bat :app:testDebugUnitTest` — Expected: PASS.

- [ ] **Step 5: traxer + commit**

Run: `traxer check`

```powershell
git add -A
git commit -m @'
refactor(loop): общий скелет agentTurnFlow для оркестраторов

Единый error-контракт (in-band Error, rethrow отмены, buffer+IO) —
одна функция вместо копии в каждом оркестраторе.

Trace: streaming-llm-chat-6595a1
Trace: rezhim-refleksii-fca3c4

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
'@
```

---

## Финальная верификация (после всех тасков)

- [ ] `.\gradlew.bat :app:testDebugUnitTest` — весь набор зелёный.
- [ ] `.\gradlew.bat :app:compileDebugKotlin` (или полная сборка `:app:assembleDebug`) — BUILD SUCCESSFUL.
- [ ] `grep -rn "import android" app/src/main/java/com/example/agent/loop app/src/main/java/com/example/agent/core/prompt` — пусто.
- [ ] `traxer check` — 0 errors; stale-warnings blessить только по подтверждённым rationale.
- [ ] superpowers:verification-before-completion, затем superpowers:finishing-a-development-branch (merge/PR в `mvp` — решает пользователь).

## Что осознанно НЕ входит (за пределами согласованного скоупа)

- Выбор провайдера (хардкод `LlmProvider.NIM` в TurnManager) — не трогаем.
- Разгрузка ChatViewModel (уроки/голос/поиск) — UI, отложено.
- Вынос эпизодической памяти / load_skill-гейта из ReactLoopRunner — отложено.
- Единый «пайплайн хода» (унификация оркестраторов в стадии) — отложено.
