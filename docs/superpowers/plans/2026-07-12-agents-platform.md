# Agents Platform (User Agent Builder + Structured Plan Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать пользователю (1) собрать, проверить и запустить собственного агента со scoped tools/skills/memory/hooks и полноэкранным экраном агента вместо шторки-инспектора; (2) заменить prompt-only PLAN/GOAL структурированным Plan Engine: persisted планы с версиями, typed approval, executor по шагам и evidence-based verification.

**Architecture:** Preset `MiniAgentCatalog` остаётся read-only; custom agents/skills/hooks — Room, объединение через `AgentResolver`. Существующий тракт `TurnManager → TurnExecutor → AgentLoopOrchestrator → ReactLoopRunner` сохраняется и получает immutable `AgentDefinition` snapshot, `ExecutionPolicy`, `HookEngine`. Plan Engine ложится на тот же тракт: фаза планирования получает только control-тулы (`submit_plan` и др.), одобрение — typed intent в `PlanRepository` (не магическое сообщение), GOAL-ход диспатчится на `PlanExecutor`, который исполняет один runnable step в бюджете и закрывает критерии только через `PlanEvidence`.

**Tech Stack:** Kotlin 2.3, Jetpack Compose Material 3, Room, Metro DI, kotlinx.coroutines/serialization, JUnit 4, Robolectric, Compose UI Test, Roborazzi, Gradle, traxer.

**Спеки:**
- `docs/superpowers/specs/2026-07-12-user-agent-builder-design.md` (+ поправка: полноэкранный экран агента вместо ModalBottomSheet, см. Task 21)
- `docs/superpowers/specs/2026-07-12-structured-plan-engine-design.md`

**Supersedes:** `docs/superpowers/plans/2026-07-12-user-agent-builder.md` — его Tasks 0–2 уже выполнены (коммиты `84adc0c`, `1ced5b1`, `148a847`), его Tasks 3–15 перенесены сюда (3–9 без изменений номеров; 10→19, 11→20, 12→21 переписан, 13→22, 14→23, 15→24).

**Включает как Фазу B:** `docs/superpowers/plans/2026-07-12-structured-plan-automation.md` (Tasks 0–16 плана-инжина) — его задачи НЕ дублируются здесь, исполняются по тому файлу с поправками согласования из раздела «Фаза B» ниже.

## Trace IDs (заморожены, `traxer id` повторно не запускать)

- Builder: `user-agent-builder-848153` (уже в `traceability.yaml`).
- Plan Engine: `structured-plan-engine-907066` — регистрируется в Task 0 automation-плана (использовать ЭТОТ id, `traxer id` не запускать).

## Global Constraints

- Не затирать текущие незакоммиченные изменения. Перед каждой задачей `git status --short`. ВНИМАНИЕ: `AgentResolver.kt` и `AgentResolverTest.kt` уже лежат в дереве незакоммиченными — Task 3 начат.
- Новые Kotlin-файлы — под `app/src/main/java/com/aiopenpocket/...`, тесты — `app/src/test/java/com/aiopenpocket/...`. Существующие файлы физически под `com/example/` не переносить; их package уже `com.aiopenpocket.*`.
- Preset-агенты не переносятся в Room и не редактируются; только клонирование. ID custom agent не пересекается с preset ID.
- Никакого пользовательского JavaScript/Kotlin; hooks не вызывают LLM/tools. Planning-фаза и verifier не получают action tools.
- `system_base.md` immutable, всегда приоритетнее user prompt/skills; текст плана — структурированные данные, не может расширить tool scope / отменить policy / изменить risk class.
- MCP runtime, workflow graph, swarm, marketplace, cloud sync, параллельный DAG, авто-включение PLAN моделью, финансовые транзакции и медицинские назначения — вне scope.
- Миграции БД: решение пользователя 2026-07-12 — `fallbackToDestructiveMigration`, явные `Migration`/schema export/migration tests НЕ добавлять. Версию БД поднимать на +1 от фактической в коде на момент задачи (не хардкодить из плана).
- Preview обязан блокировать `WRITE`, `DESTRUCTIVE`, `EXTERNAL` в коде до `ToolExecutor`.
- Custom memory/files изолированы по `agentId` кодом; prompt-инструкция не считается enforcement.
- REFLECT не обходит allowlist (закрывается в Task 4).
- `DRAFT`-агент не в roster; удаление = `ARCHIVED`. Plan: material change всегда через approval; approved `PlanVersion` immutable; required criterion не закрывается без `PlanEvidence`.
- Один conversation исполняет не более одного plan run одновременно (сериализация ходов в TurnManager это уже даёт — не ломать).
- Старую спеку `2026-07-04-agent-hooks-design.md` не реализовывать; `[[NEED_INFO]]`-маркер и `APPROVE_PLAN_MESSAGE` удаляются в Фазе B (automation Task 15 «Remove legacy prompt-only PLAN/GOAL contract»).
- Перед каждым коммитом `traxer check`; stale bless только после подтверждения rationale. Трейлеры (`Trace:`, `Co-Authored-By:`) — одним слитным блоком без пустой строки.
- Коммиты ниже — рекомендуемые границы. Не создавать commits без явного разрешения пользователя.
- CHAT и REFLECT сохраняют прежнее поведение (regression-тесты обязательны).

## Порядок фаз и зависимости

```text
Фаза A — runtime-рельсы builder (Tasks 3–9, этот файл):
  AgentResolver → composite skills → memory ns → file sandbox → widget allowlist →
  HookEngine → ExecutionPolicy/Preview/Approvals
Фаза B — Plan Engine (Tasks 0–16 файла 2026-07-12-structured-plan-automation.md):
  его Dependency Gate требует контракты Фазы A (resolver, hooks, policy,
  ReactLoopRunner с AgentDefinition/ExecutionMode) — стартовать только после 3–9
Фаза C — продуктовый UI (Tasks 19–24, этот файл):
  Builder ViewModel → визард → AgentDetailScreen (полноэкранный, + секция планов
  из PlanRepository Фазы B) → heartbeat custom agents → Academy bridge →
  docs/security/final
```

Ключевые пересечения швов (кто последний — тот аккуратнее): `ReactLoopRunner` (Tasks 4, 7, 8, 9 + Фаза B), `PrePromptAssembler` (Tasks 3, 5 + PlanContextProvider Фазы B), `TurnManager`/`TurnPorts`/`TurnModule` (Task 9 + PlanRunner Фазы B), `HeartbeatRunner` (PlanScheduler Фазы B, затем Task 22), `AppDatabase` (Tasks 4, 8 + Фаза B — каждый bump +1), экран агента (Task 21 потребляет Tasks 3, 6, 8 и Plans UI Фазы B).

## File Map

Новые production-файлы (в дополнение к уже созданным Task 1–2):

```text
app/src/main/java/com/aiopenpocket/agent/core/agent/AgentResolver.kt          (Task 3, WIP)
app/src/main/java/com/aiopenpocket/agent/core/skill/UserSkillEntity.kt        (Task 4)
app/src/main/java/com/aiopenpocket/agent/core/skill/UserSkillDao.kt           (Task 4)
app/src/main/java/com/aiopenpocket/agent/core/skill/UserSkillRepository.kt    (Task 4)
app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookEntity.kt         (Task 8)
app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookDao.kt            (Task 8)
app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookRepository.kt     (Task 8)
app/src/main/java/com/aiopenpocket/agent/core/hook/HookEngine.kt              (Task 8)
app/src/main/java/com/aiopenpocket/agent/core/policy/ExecutionPolicy.kt       (Task 9)
app/src/main/java/com/aiopenpocket/agent/core/policy/ToolApprovalCoordinator.kt (Task 9)
app/src/main/java/com/aiopenpocket/agent/core/plan/**                         (Фаза B — File Map в
                                                    2026-07-12-structured-plan-automation.md)
app/src/main/java/com/aiopenpocket/ui/plan/**                                 (Фаза B, там же)
app/src/main/java/com/aiopenpocket/ui/agents/builder/*                        (Tasks 19–20)
app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailRoute.kt       (Task 21)
app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailScreen.kt      (Task 21)
app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailViewModel.kt   (Task 21)
```

Основные изменяемые seams:

```text
app/src/main/java/com/example/agent/loop/AgentMode.kt            (Фаза B)
app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt (Tasks 3, 9; Фаза B)
app/src/main/java/com/example/agent/loop/TurnCompletion.kt        (Фаза B)
app/src/main/java/com/example/agent/loop/TurnManager.kt           (Task 9; Фаза B)
app/src/main/java/com/example/agent/loop/TurnPorts.kt             (Task 9; Фаза B)
app/src/main/java/com/example/di/TurnModule.kt                    (Task 9; Фаза B)
app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt       (Tasks 4, 7, 8, 9)
app/src/main/java/com/example/agent/loop/ReflectionOrchestrator.kt (Task 4)
app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt (Tasks 3, 5; Фаза B)
app/src/main/java/com/example/agent/core/skill/SkillRegistry.kt   (Task 4)
app/src/main/java/com/example/agent/core/skill/LoadSkillTool.kt   (Task 4)
app/src/main/java/com/example/agent/tools/MemoryTools.kt          (Task 5)
app/src/main/java/com/example/agent/tools/FileTools.kt            (Task 6)
app/src/main/java/com/example/agent/tools/RenderCardTool.kt       (Task 7)
app/src/main/java/com/example/data/database/AppDatabase.kt        (Tasks 4, 8; Фаза B)
app/src/main/java/com/example/di/DatabaseBindings.kt              (Tasks 4, 8; Фаза B)
app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt    (Фаза B; Task 22)
app/src/main/java/com/example/agent/core/memory/entity/HeartbeatEntity.kt (Фаза B: typed planId)
app/src/main/assets/prompts/modes/plan_planning.md                (Фаза B, снос в её Task 15)
app/src/main/assets/prompts/modes/goal.md                         (Фаза B, снос в её Task 15)
app/src/main/java/com/example/ui/chat/*                           (Task 9; Фаза B)
app/src/main/java/com/example/ui/agents/*                         (Tasks 3, 21)
app/src/main/java/com/example/MainActivity.kt                     (Фаза B; Tasks 20, 21)
traceability.yaml                                                 (Фаза B Task 0)
AGENTS.md                                                         (Task 24)
```

---

## Выполнено (из старого builder-плана, для контекста)

- **Task 0** — baseline + `user-agent-builder-848153` в `traceability.yaml` (`84adc0c`).
- **Task 1** — `AgentDefinition`/`AgentValidator`/`AgentHook` (domain) + `MiniAgent.toDefinition()` (`1ced5b1`).
- **Task 2** — Room: `UserAgentEntity`/`UserAgentDao`/`UserAgentRepository` + транзакционный publish (`148a847`).

---

### Task 3: Ввести AgentResolver и убрать runtime-зависимость от каталога

**СТАТУС: начат** — `app/src/main/java/com/aiopenpocket/agent/core/agent/AgentResolver.kt` и его тест уже лежат в рабочем дереве незакоммиченными. Сначала прочитать их и продолжить, а не переписывать с нуля.

**Files:**
- Create (WIP): `app/src/main/java/com/aiopenpocket/agent/core/agent/AgentResolver.kt`
- Modify: `app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentsViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentsScreen.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentInspectorSheet.kt` (минимально — шторку целиком заменит Task 21)
- Modify: `app/src/main/java/com/example/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt`
- Test (WIP): `app/src/test/java/com/aiopenpocket/agent/core/agent/AgentResolverTest.kt`

**Interfaces:**
- Consumes: preset adapter `MiniAgent.toDefinition()` + `UserAgentRepository` (Tasks 1–2).
- Produces: `AgentResolver.resolve(id: String?): AgentDefinition?`, `AgentResolver.observeRoster(): Flow<List<AgentDefinition>>`, `PromptContext.agent: AgentDefinition?` — единственный resolve на ход, снапшот передаётся вниз без повторных обращений.

- [ ] **Step 1: Прочитать WIP-файлы и дописать failing resolver tests**

```kotlin
assertEquals(AgentSource.PRESET, resolver.resolve("budget")!!.source)
assertEquals(AgentSource.USER, resolver.resolve(custom.id)!!.source)
assertNull(resolver.resolve("missing"))
assertEquals(listOf("budget", custom.id), resolver.observeRoster().first().map { it.id })
```

Отдельно проверить, что archived/draft отсутствуют в roster и preset имеет приоритет при collision.

- [ ] **Step 2: Реализовать resolver**

```kotlin
interface AgentResolver {
  suspend fun resolve(id: String?): AgentDefinition?
  fun observeRoster(): Flow<List<AgentDefinition>>
}
```

`observeRoster()` объединяет immutable preset list и published Room flow. `resolve()` в горячем пути хода — один DAO-запрос на ход. Повторных resolve ниже по стеку не делать.

- [ ] **Step 3: Перевести PrePromptAssembler**

`assemble()` уже suspend:

```kotlin
val agent = agentResolver.resolve(conversation?.agentId)
val skillIds = agent?.skillIds
val toolNames = agent?.toolNames
```

Prompt сохраняет порядок из builder-спеки и существующие секции assembler (язык, персона, tutorial-промпт, `<user_data>`-фенсинг). Shared profile — только при `memoryPolicy.includeSharedProfile`. `PromptContext` расширяется полем `agent: AgentDefinition?`; оркестратор передаёт снапшот в `TurnContextHolder`/`ReactLoopRunner` без повторного resolve.

- [ ] **Step 4: Перевести остальные direct references**

Run: `rg "MiniAgentCatalog\.(get|all)" app/src/main/java`
Expected после изменения: direct references остаются только в preset adapter и preview/test fixtures.

- [ ] **Step 5: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.*" --tests "com.aiopenpocket.agent.core.prompt.*" --tests "com.aiopenpocket.agent.workers.HeartbeatRunnerTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git commit -m "refactor(agents): resolve preset and custom definitions"
```

(с трейлерами `Trace: user-agent-builder-848153` + `Co-Authored-By` слитным блоком)

---

### Task 4: Сделать SkillRegistry composite и добавить custom SKILL.md

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/skill/UserSkillEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/skill/UserSkillDao.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/skill/UserSkillRepository.kt`
- Modify: `app/src/main/java/com/example/agent/core/skill/SkillRegistry.kt`
- Modify: `app/src/main/java/com/example/agent/core/skill/LoadSkillTool.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReflectionOrchestrator.kt`
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt` (version +1)
- Modify: `app/src/main/java/com/example/di/DatabaseBindings.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/skill/CompositeSkillRegistryTest.kt`

**Interfaces:**
- Consumes: `AgentDefinition.skillIds` (Task 1), agent snapshot (Task 3).
- Produces: suspend `SkillRegistry.get(id)`, `ids()`, `catalog(allowedIds)`; `ReactLoopRunner.run(..., agent: AgentDefinition? = null, ...)` с гейтом `isSkillAllowed`.

- [ ] **Step 1: Написать failing tests**

Проверить built-in lookup, Room lookup, duplicate ID rejection, owner isolation, size limit (`MAX_CUSTOM_SKILL_CHARS = 40_000` из `AgentValidator`) и запрет загрузки custom skill другого агента.

- [ ] **Step 2: Изменить registry contract**

```kotlin
suspend fun get(id: String): Skill?
suspend fun ids(): Set<String>
suspend fun catalog(allowedIds: Set<String>? = null): String
```

Built-in map остаётся из Metro; Room repository — второй источник. При collision built-in побеждает, а validator запрещает публикацию collision.

- [ ] **Step 3: Передать allowedSkillIds в loop**

Заменить `boundAgentId`-gate:

```kotlin
suspend fun run(
  ...,
  agent: AgentDefinition? = null,
  ...
)

private fun isSkillAllowed(arguments: Map<String, JsonElement>, agent: AgentDefinition?): Boolean {
  if (agent == null) return true
  val requestedId = (arguments["id"] as? JsonPrimitive)?.content ?: return true
  return requestedId in agent.skillIds
}
```

- [ ] **Step 4: Закрыть REFLECT-обход скоупа**

Сегодня `ReflectionOrchestrator` вызывает `reactLoopRunner.run(..., registry.getAllSchemas(), ...)` без agent: custom agent в рефлексии получил бы ПОЛНЫЙ набор тулов мимо allowlist. Передать в draft/revise фазы scoped-схемы (`context.toolNames` из PromptContext) и `agent`-снапшот — как в `AgentLoopOrchestrator`. Тест: у custom agent с одним разрешённым тулом REFLECT-ход не может вызвать чужой тул (`Tool ... is not available in this context`).

- [ ] **Step 5: Включить custom skills в publish transaction**

Agent draft, skills и hooks записываются атомарно; при invalid Markdown transaction откатывается.

- [ ] **Step 6: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.skill.*" --tests "com.aiopenpocket.agent.loop.*"
```

Expected: PASS. Commit: `feat(skills): load agent-owned Room skills` (+Trace-трейлер `user-agent-builder-848153`).

---

### Task 5: Enforce per-agent memory namespace

**Files:**
- Modify: `app/src/main/java/com/example/agent/loop/TurnContextHolder.kt`
- Modify: `app/src/main/java/com/example/agent/tools/MemoryTools.kt`
- Modify: `app/src/main/java/com/example/agent/core/memory/dao/FactDao.kt`
- Modify: `app/src/main/java/com/example/agent/core/memory/repository/MemoryRepository.kt`
- Modify: `app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/tools/MemoryToolsNamespaceTest.kt`

**Interfaces:**
- Consumes: `PromptContext.agent` (Task 3).
- Produces: `TurnContextHolder.Ctx(conversationId, agent)`; writes/search scoped к `agent:{id}:` кодом.

- [ ] **Step 1: Написать security regression tests**

```kotlin
holder.set("conv", custom.id)
memoryTool.execute(remember("note", "secret"))
assertEquals("agent:${custom.id}:note", repository.saved.single().key)
assertFalse(memoryTool.execute(search("agent:other:")).toString().contains("other-secret"))
```

Проверить, что plain chat сохраняет старую global semantics.

- [ ] **Step 2: Расширить context snapshot**

```kotlin
data class Ctx(
  val conversationId: String?,
  val agent: AgentDefinition?,
)
```

Не резолвить agent внутри tools: snapshot кладёт оркестратор из `PromptContext.agent` (single resolve per turn, см. Task 3).

- [ ] **Step 3: Добавить scoped repository methods**

```kotlin
suspend fun searchFactsInNamespace(namespace: String, query: String): List<FactEntity>
suspend fun getFactsByNamespace(namespace: String): List<FactEntity>
```

DAO query включает `key LIKE :namespace || '%'`.

- [ ] **Step 4: Enforce MemoryTools**

```kotlin
val storedKey = context.agent?.memoryPolicy?.namespace?.plus(userKey) ?: userKey
```

Custom path не может назначать `profile`/`preference`; category принудительно `general`. Shared profile только читается assembler.

- [ ] **Step 5: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.tools.MemoryToolsNamespaceTest" --tests "com.aiopenpocket.agent.core.prompt.*"
```

Expected: PASS. Commit: `feat(memory): isolate custom agent facts`.

---

### Task 6: Enforce per-agent file sandbox

**Files:**
- Modify: `app/src/main/java/com/example/agent/tools/FileTools.kt`
- Modify: `app/src/main/java/com/example/data/repository/AgentFilesRepository.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentInspectorViewModel.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/tools/FileToolsSandboxTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/data/repository/AgentFilesRepositoryTest.kt`

**Interfaces:**
- Consumes: `TurnContextHolder.Ctx.agent` (Task 5).
- Produces: `sandboxRoot(filesDir, agentId)`; `AgentFilesRepository.list(agentId: String): List<AgentFile>` — потребляется Task 21 (экран агента).

- [ ] **Step 1: Написать failing isolation tests**

Создать `agents/a/private.txt` и `agents/b/private.txt`; agent A видит только A. Проверить `../b/private.txt`, absolute path, mixed `\`/`/` и canonical traversal.

- [ ] **Step 2: Вынести root resolver**

```kotlin
internal fun sandboxRoot(filesDir: File, agentId: String?): File =
  if (agentId == null) File(filesDir, "agent")
  else File(File(filesDir, "agents"), agentId)
```

Custom ID уже прошёл regex validator.

- [ ] **Step 3: Использовать TurnContextHolder в FileTools**

Все операции одного вызова используют один snapshot root; не вычислять root повторно после suspension.

- [ ] **Step 4: Исправить Inspector repository**

`suspend fun list(agentId: String): List<AgentFile>`. Удалить текст UI «общая песочница».

- [ ] **Step 5: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.tools.FileToolsSandboxTest" --tests "com.aiopenpocket.data.repository.AgentFilesRepositoryTest"
```

Expected: PASS. Commit: `feat(files): isolate custom agent sandboxes`.

---

### Task 7: Добавить widget allowlist

**Files:**
- Modify: `app/src/main/java/com/example/agent/tools/RenderCardTool.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/tools/RenderCardPolicyTest.kt`

**Interfaces:**
- Consumes: `TurnContextHolder.Ctx.agent`, `AgentDefinition.allowedWidgetTemplates`.
- Produces: runtime rejection запрещённых templates; константы `GENERAL_CARD_TEMPLATES`/`LESSON_CARD_TEMPLATES`.

- [ ] **Step 1: Написать failing tests**

Проверить allowed `todo_list`, denied `lesson_quiz`, unknown template и plain chat behavior.

- [ ] **Step 2: Разделить template constants**

```kotlin
val GENERAL_CARD_TEMPLATES = setOf(
  "todo_list", "weather_card", "timer", "chart_bar", "table", "form",
  "choice", "switch", "notification_permission", "reminder", "image",
)
val LESSON_CARD_TEMPLATES = setOf(
  "lesson_quiz", "lesson_complete", "reveal", "concept", "example",
  "fill", "sort", "match", "scenario",
)
```

- [ ] **Step 3: Enforce в tool execute**

`RenderCardTool` читает agent snapshot из `TurnContextHolder` и возвращает non-recoverable `ToolResult.Error` до формирования card JSON.

- [ ] **Step 4: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.tools.RenderCardPolicyTest" --tests "com.aiopenpocket.ui.chat.DrivenUiEngineTest"
```

Expected: PASS. Commit: `feat(cards): enforce agent widget allowlists`.

---

### Task 8: Реализовать declarative HookEngine

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookDao.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookRepository.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/HookEngine.kt`
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt` (version +1)
- Modify: `app/src/main/java/com/example/di/DatabaseBindings.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/hook/HookEngineTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/ReactLoopHooksTest.kt`

**Interfaces:**
- Consumes: domain `AgentHook`/`HookEvent`/`HookActionType` (Task 1).
- Produces: `HookEngine.evaluate(event, hooks, context): HookDecision`; журнал `agent_hook_runs` (потребляется Task 21).

- [ ] **Step 1: Написать pure HookEngine tests**

Проверить ordering `(priority, id)`, action/event compatibility, `DENY > ALLOW`, condition mismatch, execution limit, `ON_ERROR` non-recursion.

- [ ] **Step 2: Зафиксировать модель решения**

```kotlin
data class HookDecision(
  val addedInstructions: List<String> = emptyList(),
  val denied: Boolean = false,
  val approvalRequired: Boolean = false,
  val stopped: Boolean = false,
  val logs: List<String> = emptyList(),
)
```

- [ ] **Step 3: Реализовать action matrix**

```text
BEFORE_TURN: ADD_INSTRUCTION, LOG, STOP
AFTER_TURN: LOG, STOP
BEFORE_TOOL: REQUIRE_APPROVAL, ALLOW, DENY, LOG, STOP
AFTER_TOOL: LOG, STOP
ON_ERROR: LOG, STOP
ON_COMPLETE: LOG
```

Invalid combination блокирует publish через `AgentValidator`.

- [ ] **Step 4: Встроить lifecycle в ReactLoopRunner**

Перед каждой `streamChat` — `BEFORE_TURN`; после collect — `AFTER_TURN`; вокруг каждого tool — `BEFORE_TOOL/AFTER_TOOL`; перед error return — `ON_ERROR`; перед successful return — `ON_COMPLETE`. `ADD_INSTRUCTION` добавляет ephemeral system message только для текущей итерации и удаляет его после неё. ВАЖНО: вставлять/удалять ТОЛЬКО в голове списка (сразу после базового system-сообщения) — `messages` мутируется in-place, в хвосте живут пары assistant(tool_calls)/tool; разрыв пары даёт HTTP 400 у строгих провайдеров. Hook никогда не вызывает LLM/tool.

- [ ] **Step 5: Добавить persistence и журнал**

Хранить последние 100 run records на hook; cleanup после insert. Repository возвращает hooks отсортированными.

- [ ] **Step 6: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.hook.*" --tests "com.aiopenpocket.agent.loop.ReactLoopHooksTest"
```

Expected: PASS. Commit: `feat(hooks): add declarative lifecycle engine`.

---

### Task 9: Ввести ExecutionPolicy, Preview и approvals

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/policy/ExecutionPolicy.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/policy/ToolApprovalCoordinator.kt`
- Modify: `app/src/main/java/com/example/agent/core/mcp/Tool.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnManager.kt`
- Modify: `app/src/main/java/com/example/agent/loop/TurnPorts.kt`
- Modify: `app/src/main/java/com/example/di/TurnModule.kt`
- Modify: mixed-action tools в `app/src/main/java/com/example/agent/tools/`
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopEvent.kt` если события разделяются; иначе `AgentLoopOrchestrator.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatIntent.kt`, `ChatUiState.kt`, `ChatViewModel.kt`, `ChatScreen.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/policy/ExecutionPolicyTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/ToolApprovalFlowTest.kt`

**Interfaces:**
- Consumes: tool `RiskClass` + `BEFORE_TOOL` `HookDecision` (Task 8).
- Produces: `PolicyDecision` (Execute/Simulate/Deny/AwaitApproval), `Tool.riskFor(arguments)`, `ExecutionMode` (LIVE/PREVIEW) через `TurnManager.launchTurn` → `TurnExecutor` — потребляется Фазой B (PlanExecutor/PlanRunner) и Task 20 (builder preview).

- [ ] **Step 1: Написать policy table tests**

```text
PREVIEW + READ        → EXECUTE
PREVIEW + WRITE       → SIMULATE
PREVIEW + DESTRUCTIVE → SIMULATE
PREVIEW + EXTERNAL    → SIMULATE
LIVE + READ           → EXECUTE
LIVE + risky          → agent rule, never weaker than global floor
hook DENY             → DENY
hook REQUIRE_APPROVAL → AWAIT_APPROVAL
```

- [ ] **Step 2: Реализовать pure evaluator**

```kotlin
sealed interface PolicyDecision {
  data object Execute : PolicyDecision
  data class Simulate(val reason: String) : PolicyDecision
  data class Deny(val reason: String) : PolicyDecision
  data class AwaitApproval(val request: ToolApprovalRequest) : PolicyDecision
}
```

- [ ] **Step 3: Добавить action-level risk**

Расширить `Tool` без поломки существующих реализаций:

```kotlin
fun riskFor(arguments: Map<String, JsonElement>): RiskClass = riskClass
```

Переопределить для mixed-action tools минимум:

```text
file_tool: read_file/list_dir/search_files=READ, write_file=WRITE
memory_tool: search_memory/recall_episodes=READ, remember_fact=WRITE
calendar: list_events=READ, create_event=WRITE, delete_event=DESTRUCTIVE
database_query: SELECT=READ, INSERT/UPDATE=WRITE, DELETE=DESTRUCTIVE
heartbeat/work_manager: list=READ, schedule=WRITE, cancel=DESTRUCTIVE
```

Table-driven tests на каждую mapping. Не определять risk по непроверенной подстроке SQL — переиспользовать существующий SQL parser/validator.

- [ ] **Step 4: Реализовать coordinator**

Singleton coordinator хранит `CompletableDeferred<Boolean>` по `requestId`, публикует pending requests через `StateFlow`, удаляет entry при resolve/cancel. Нельзя подтверждать request другого run. Approval без ответа = deny: таймаут 120 с и уход приложения в фон резолвят deferred в `false` (recoverable policy denial). Ходы живут в appScope-`TurnManager` — pending approval не имеет права подвесить фоновый ход навечно.

- [ ] **Step 5: Встроить gate до ToolExecutor**

```text
schema allowlist → skill/widget checks → BEFORE_TOOL → ExecutionPolicy →
optional approval → ToolExecutor
```

Simulation возвращает модели JSON: `{"status":"preview_only","executed":false,"tool":"calendar","arguments":{}}`.

- [ ] **Step 6: Пробросить ExecutionMode через TurnManager**

`ExecutionMode` (LIVE по умолчанию, PREVIEW из builder-теста) едет через существующий тракт: `TurnManager.launchTurn(...)` → `TurnExecutor.execute(...)` → `AgentLoopOrchestrator.execute(...)` → `ReactLoopRunner`. Сохранить mode в `TurnSession` рядом с retry-контекстом, чтобы «Повторить» переигрывал ход в том же режиме. НЕ заводить параллельный вход для preview — builder-тест использует тот же TurnManager (отдельная test conversation, см. Task 20).

- [ ] **Step 7: Добавить Chat UI**

`AgentEvent.ToolApprovalRequired`, state `pendingToolApproval`, intents approve/deny, modal/bar с tool name, risk, arguments. Убрать ограничение, из-за которого approval UI показывается только при `agentId == null`.

- [ ] **Step 8: Проверить cancellation**

Cancel run завершает pending deferred, убирает UI и не выполняет tool. Background heartbeat никогда не ждёт UI: approval decision превращается в deny.

- [ ] **Step 9: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.policy.*" --tests "com.aiopenpocket.agent.loop.ToolApprovalFlowTest"
```

Expected: PASS. Commit: `feat(tools): enforce preview and approval policy`.

---

## Фаза B: Structured Plan Engine — исполняется по отдельному плану

Задачи плана-инжина НЕ дублируются здесь. Единственный источник Фазы B —
`docs/superpowers/plans/2026-07-12-structured-plan-automation.md` (Tasks 0–16:
домен/стейт-машина → валидация → Room → planner control schemas → typed approval
→ PlanContextProvider → пошаговый executor с бюджетами → verifier/evidence →
plan-хуки → PlanRunner и миграция PLAN/GOAL → typed PlanScheduler → PlanCard/
PlanDetail UI → доменные шаблоны → recovery → снос legacy-контракта →
security/docs). Его Dependency Gate требует builder-контракты — поэтому Фаза B
стартует только после Фазы A (Tasks 3–9) целиком.

### Поправки согласования (этот блок сильнее текста automation-плана)

1. **Trace ID уже заморожен**: `structured-plan-engine-907066`. В его Task 0
   Step 3 `traxer id` НЕ запускать — использовать этот id в `traceability.yaml`,
   `@trace`-маркерах и трейлерах коммитов.
2. **Миграции БД**: его Task 3 создаёт `AppDatabaseMigrations.kt`, schema export
   и правку `app/build.gradle.kts` — НЕ делать. Действует решение пользователя
   2026-07-12: `fallbackToDestructiveMigration`, только `version +1`, без
   `Migration`/export/migration-tests (см. Global Constraints этого плана).
3. **Точка входа Plans UI**: его Task 12 Step 4 вешает секцию планов на
   `AgentInspectorSheet` — шторка удаляется в Task 21 (Фаза C). Маршруты
   `plans/{agentId}` и `plan/{planId}` остаются как есть; секция «Планы» живёт
   на полноэкранном `AgentDetailScreen` (Task 21). Если Task 12 исполняется до
   Task 21 — добавить в шторку минимальную навигационную строку и перенести её
   в Task 21; в шторку ничего сложного не встраивать.
4. **Heartbeat-шов**: его Task 11 (typed `PlanScheduler`) и builder Task 22
   (custom agents в heartbeat) правят одни файлы (`HeartbeatRunner`,
   `HeartbeatTool`, `HeartbeatEntity/Dao`). Task 22 выполняется ПОСЛЕ Фазы B и
   не дублирует deny-ветку background-approval — выносит общий guard.
5. **Промпт-ассеты**: его Task 15 удаляет legacy `[[NEED_INFO]]`/plan-режимные
   секции и `APPROVE_PLAN_MESSAGE` — не редактировать
   `prompts/modes/plan_planning.md`/`goal.md` в других задачах параллельно;
   новые ассеты живут в `prompts/plan/planner.md|executor.md|verifier.md`.
6. **Порядок коммитов**: у каждой задачи Фазы B трейлер
   `Trace: structured-plan-engine-907066` (слитным блоком с `Co-Authored-By`).

---

### Task 19: Builder state, validation и publish ViewModel

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderUiState.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderValidator.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderViewModel.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/builder/AgentBuilderViewModelTest.kt`

**Interfaces:**
- Consumes: `UserAgentRepository` (Task 2), `AgentResolver` (Task 3), `SkillRegistry.ids()` (Task 4), `AgentValidator` (Task 1), `ExecutionMode` (Task 9).
- Produces: six-step draft state, autosave, preview launch, publish; route-контракт для Task 20.

- [ ] **Step 1: Написать failing ViewModel tests**

Проверить new draft, clone preset, autosave debounce, validation per step, publish only valid, archive, edit published creates new configVersion, restoration after ViewModel recreation.

- [ ] **Step 2: Зафиксировать state/events**

```kotlin
enum class BuilderStep { IDENTITY, INSTRUCTIONS, CAPABILITIES, LOOP, HOOKS, TEST }

data class AgentBuilderUiState(
  val draft: AgentDraft,
  val step: BuilderStep,
  val errors: List<AgentValidationError>,
  val isSaving: Boolean,
  val canPublish: Boolean,
  val executionMode: ExecutionMode,
)
```

- [ ] **Step 3: Реализовать autosave**

Изменения draft — через `MutableStateFlow`, `debounce(500)`, repository transaction. Ошибка сохранения остаётся в state и не теряет in-memory draft.

- [ ] **Step 4: Реализовать clone/publish**

Clone создаёт новый UUID/slug ID, source USER, namespace по новому ID, status DRAFT. Publish вызывает единый domain validator и repository transaction. Clone — это и есть «форк дефолтного агента»: с экрана пресета (Task 21) открывается builder с префиллом всех полей пресета и новым именем «Копия: <имя>».

- [ ] **Step 5: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.agents.builder.AgentBuilderViewModelTest"
```

Expected: PASS. Commit: `feat(agents): add builder state and validation`.

---

### Task 20: Six-step Compose wizard и navigation

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderRoute.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderScreen.kt`
- Create: focused components под `app/src/main/java/com/aiopenpocket/ui/agents/builder/components/`
- Modify: `app/src/main/java/com/example/MainActivity.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/builder/AgentBuilderScreenTest.kt`

**Interfaces:**
- Consumes: Task 19 ViewModel; `TurnManager` + `ExecutionMode.PREVIEW` (Task 9).
- Produces: routes `agent_builder` и `agent_builder/{agentId}`; testTags ниже — контракт для скриншот-тестов Task 24.

- [ ] **Step 1: Написать Compose tests**

testTags:

```text
agent_builder_next, agent_builder_back, agent_builder_tool_<name>,
agent_builder_skill_<id>, agent_builder_hook_add, agent_builder_preview,
agent_builder_live_test, agent_builder_publish
```

Проверить блокировку next при invalid identity и publish при invalid hooks.

- [ ] **Step 2: Добавить navigation**

```kotlin
composable("agent_builder") { AgentBuilderRoute(agentId = null, ...) }
composable("agent_builder/{agentId}") { backStack ->
  AgentBuilderRoute(agentId = backStack.arguments?.getString("agentId"), ...)
}
```

- [ ] **Step 3: Реализовать шаги**

Небольшие composables: Identity, Instructions, Capabilities, LoopModes, Hooks, TestAndPublish. Переиспользовать onboarding progress pattern, не копировать весь `OnboardingScreen`.

- [ ] **Step 4: Реализовать risk rails**

Tool row показывает `riskClass`, permission и consequence. Первый выбор `WRITE/DESTRUCTIVE/EXTERNAL` открывает consent; отказ не включает tool. `database_query` и `sms` скрыты из default-набора builder, но доступны в explicit «Advanced tools» с отдельным предупреждением.

- [ ] **Step 5: Реализовать custom skill/hook editors**

Skill editor: char count и manifest fields. Hook editor: только event-compatible actions; raw JSON пользователю не показывать.

- [ ] **Step 6: Добавить Preview/Live test**

Preview создаёт/использует отдельную test conversation и запускает существующий TurnManager с `ExecutionMode.PREVIEW`. Live test требует явного переключения.

- [ ] **Step 7: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.agents.builder.AgentBuilderScreenTest"
.\gradlew.bat :app:verifyRoborazziDebug
```

Expected: PASS; скриншот-baseline обновлять только после визуального review. Commit: `feat(agents): add six-step builder UI`.

---

### Task 21: AgentDetailScreen — полноэкранный экран агента (замена шторки)

Закрывает исходные UX-жалобы: (1) шторка неудобна при скролле → обычный экран со своим скроллом; (2) препромпт разворачивается; (3) вместо счётчиков — реальные списки тулов/навыков/хуков.

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailRoute.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailScreen.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailViewModel.kt`
- Modify: `app/src/main/java/com/example/MainActivity.kt` (route `agent/{agentId}`)
- Modify: `app/src/main/java/com/example/ui/agents/AgentsScreen.kt` (тап по карточке → navigate, не шторка)
- Modify: `app/src/main/java/com/example/ui/agents/AgentsViewModel.kt` (roster на `AgentResolver.observeRoster()`, секции «Шаблоны»/«Мои агенты», FAB «Создать агента»)
- Delete: `app/src/main/java/com/example/ui/agents/AgentInspectorSheet.kt`
- Delete/Replace: `app/src/main/java/com/example/ui/agents/AgentInspectorViewModel.kt` → логика переезжает в `AgentDetailViewModel`
- Delete: `app/src/test/java/com/aiopenpocket/ui/agents/AgentInspectorSheetScreenshotTest.kt` (+ его baseline-скриншоты)
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/AgentsViewModelTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/detail/AgentDetailViewModelTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/detail/AgentDetailScreenshotTest.kt` (новые Roborazzi baseline)

**Interfaces:**
- Consumes: `AgentResolver.resolve/observeRoster` (Task 3), `ToolRegistry` (имена+описания+riskClass), `SkillRegistry.get` (Task 4), `AgentHookRepository` + журнал (Task 8), `AgentFilesRepository.list(agentId)` (Task 6), `PlanRepository`/`PlanListViewModel` и маршруты `plans/{agentId}`, `plan/{planId}` (Фаза B, automation Task 12), heartbeat-репозиторий (как в старом инспекторе).
- Produces: route `agent/{agentId}`; действия PRESET: «Открыть чат», «Дублировать» (→ `agent_builder` с префиллом-клоном); USER: + «Изменить» (→ `agent_builder/{agentId}`), «Отключить/Включить», «Архивировать».

- [ ] **Step 1: Написать failing ViewModel tests**

```kotlin
// state собирает: definition, resolved tool rows (имя+описание+risk из ToolRegistry по agent.toolNames),
//   resolved skill rows (по agent.skillIds из composite SkillRegistry), hooks + последние hook runs,
//   активные heartbeats, files(agentId), plans (PlanRepository Фазы B), configVersion
// roster (AgentsViewModelTest): deterministic секции «Шаблоны»/«Мои агенты», no drafts/archived,
//   open chat создаёт conversation с agentId, preset edit недоступен, custom edit доступен
// preset → editable=false, дублируемость=true; user → editable=true
// неизвестный тул в definition (после обновления APK) → строка «недоступен», агент помечен invalid, не краш
```

- [ ] **Step 2: Compose/screenshot tests**

Экран — обычный `Column` + `verticalScroll` на аврора-фоне (использовать `AcademyCollapsingScaffold`, как на остальных полноэкранных вкладках — единый глассморфизм-паттерн). Секции сверху вниз:

```text
Header (иконка, имя, id, статус/configVersion) + кнопка «Открыть чат»
Действия (по source: Дублировать / Изменить / Отключить / Архивировать)
Стат-плитки (как раньше, но кликабельные — скроллят к секции)
Препромпт — ПОЛНЫЙ текст в потоке скролла, БЕЗ heightIn/вложенного скролла;
  если > 40 строк — свёртка «Развернуть/Свернуть», дефолт для экрана — развёрнуто
Инструменты — список по именам: описание + risk-бейдж (не «6 Тулов»)
Навыки — реальные skillIds: название/описание skill-пака (не «есть/нет»)
Планы — секция со счётчиками active/paused/completed и списком (данные Фазы B);
  у PROGRAM — next due; тап → plan/{planId} (PlanDetailScreen с timeline);
  сюда переезжает навигационная строка, временно добавленная в шторку
  automation Task 12 Step 4 (см. поправку согласования №3)
Heartbeat — как в старом инспекторе
Хуки — РЕАЛЬНЫЕ hooks агента + последние записи журнала; у пресетов честная пустая карточка
  (фейковые on_tool_call/pre_message-заглушки удаляются)
Файлы — песочница агента filesDir/agents/{id} (Task 6)
```

testTags: `agent_detail_scroll`, `agent_detail_prompt`, `agent_detail_prompt_toggle`, `agent_detail_tool_<name>`, `agent_detail_skill_<id>`, `agent_detail_plan_<id>`, `agent_detail_action_duplicate`, `agent_detail_action_edit`, `agent_detail_action_archive`.

- [ ] **Step 3: Реализовать экран и навигацию**

Вкладка «Файлы» из шторки становится секцией (один скролл — без внутренних табов). Переиспользовать `capabilityLabels`/`iconFor` и glass-компоненты. `AgentsScreen`: тап по карточке агента — `navController.navigate("agent/${agent.id}")`; шторочный state/`SheetState` удалить.

- [ ] **Step 4: Снести шторку и прогнать всё**

Run:

```powershell
rg "AgentInspectorSheet" app/src
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.agents.*"
.\gradlew.bat :app:verifyRoborazziDebug
```

Expected: `rg` — 0 совпадений; PASS; новые baseline после визуального review. Commit: `feat(agents): full-screen agent detail replaces inspector sheet` (+оба Trace-трейлера: `user-agent-builder-848153` и `structured-plan-engine-907066` — экран потребляет обе фичи; маркер `agent-inspector-6e41eb` в traceability.yaml обновить на новый экран или пометить superseded).

---

### Task 22: Поддержать custom agents в heartbeat

**Files:**
- Modify: `app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt`
- Modify: `app/src/main/java/com/example/agent/tools/HeartbeatTool.kt` (валидация создания)
- Test: `app/src/test/java/com/aiopenpocket/agent/workers/HeartbeatRunnerTest.kt`

**Interfaces:**
- Consumes: `AgentResolver` (Task 3), `ExecutionPolicy` (Task 9).
- Produces: безопасный background-запуск custom agent.

- [ ] **Step 1: Написать failing tests**

Проверить published custom agent, archived rejection, tool intersection, approval-required denial, config snapshot, missing agent.

- [ ] **Step 2: Resolve через AgentResolver**

```kotlin
val agent = resolver.resolve(heartbeat.agentId)
  ?: return HeartbeatRunResult.Skipped("Agent not found")
if (!agent.enabled) return HeartbeatRunResult.Skipped("Agent is not published")
```

- [ ] **Step 3: Ограничить tools**

`val tools = agent.toolNames intersect SAFE_TOOL_NAMES`, затем policy. Background никогда не создаёт pending UI approval (согласовано с `PlanScheduler` из Фазы B, automation Task 11 — не дублировать deny-ветку, вынести общий guard).

- [ ] **Step 4: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.workers.HeartbeatRunnerTest"
```

Expected: PASS. Commit: `feat(heartbeat): run published custom agents safely`.

---

### Task 23: Academy → Builder bridge

**Files:**
- Modify: `app/src/main/assets/lessons/agent_blueprint.json`
- Modify: `app/src/main/java/com/example/agent/core/lesson/LessonChatController.kt`
- Modify: `app/src/main/java/com/example/ui/chat/DrivenUiEngine.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt`
- Modify: navigation callback в `app/src/main/java/com/example/MainActivity.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/lesson/LessonAssetsValidationTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/chat/DrivenUiEngineTest.kt`

**Interfaces:**
- Consumes: route `agent_builder` (Task 20).
- Produces: CTA «Собрать этого агента» после завершения `agent_blueprint` → новый draft с `sourceContext = ACADEMY_CAPSTONE`.

- [ ] **Step 1: Написать failing card/parser tests**

Расширить `lesson_complete` params:

```json
{
  "primary_action": {
    "type": "open_agent_builder",
    "label": "Собрать этого агента"
  }
}
```

Parser отвергает unknown action types.

- [ ] **Step 2: CTA только для capstone**

Обычные lessons сохраняют старое completion behavior. CTA — после реального завершения `agent_blueprint`, не при ручном открытии card JSON.

- [ ] **Step 3: Передать source metadata**

Открывается пустой draft с `sourceContext = ACADEMY_CAPSTONE`; свободные сообщения ученика через LLM не парсить.

- [ ] **Step 4: Запустить tests и commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.lesson.LessonAssetsValidationTest" --tests "com.aiopenpocket.ui.chat.DrivenUiEngineTest"
```

Expected: PASS. Commit: `feat(academy): connect capstone to agent builder`.

---

### Task 24: Документация, security regression и финальная верификация

Финал всей платформы. Automation Task 16 (security/docs плана-инжина) выполняется в Фазе B как обычно; здесь — сквозной прогон на объединённом результате (builder + plan engine + новый экран), поэтому дубли проверок допустимы и желательны.

**Files:**
- Modify: `AGENTS.md`
- Modify: `traceability.yaml`
- Modify: relevant tests из Tasks 3–23
- Create: `app/src/test/java/com/aiopenpocket/agent/security/UserAgentIsolationTest.kt`
- Create: `app/src/test/java/com/aiopenpocket/agent/security/PlanEngineGuardrailsTest.kt`

- [ ] **Step 1: Consolidated security tests**

`UserAgentIsolationTest`:

```text
hallucinated tool outside allowlist / custom skill outside skillIds /
foreign memory namespace / foreign file sandbox / forged widget template /
preview side effect / denied approval / recursive ON_ERROR /
edit config during run / background approval
```

`PlanEngineGuardrailsTest`:

```text
planning-фаза не может вызвать action tool / executor не может вызвать тул вне
step.expectedTools / required criterion без evidence не закрывается /
approved version immutable / material change без approval не применяется /
prompt-injection в objective не расширяет tool scope / GOAL без плана не
исполняет action tools до approval / verifier без tools
```

- [ ] **Step 2: Обновить документацию**

`AGENTS.md`: фактические DB name/version, custom agents, composite skills, declarative hooks, preview/live, namespaces, Plan Engine (state machine, control tools, evidence), MCP как future extension. Пометить superseded: `2026-07-04-agent-hooks-design.md` и старый план `2026-07-12-user-agent-builder.md` (не удалять). В `traceability.yaml` обновить запись `agent-inspector-6e41eb` (шторка → полноэкранный экран) и `rezhimy-agenta-plan-i-goal-ee2361` (rationale теперь про structured engine; bless после подтверждения).

- [ ] **Step 3: Полный прогон**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:verifyRoborazziDebug
traxer check
```

Expected: все BUILD SUCCESSFUL; traxer 0 errors, stale разобраны вручную.

- [ ] **Step 4: Manual smoke test (device/emulator)**

1. Открыть агента с «Тулов» — полноэкранный экран, скролл без шторочных глюков, препромпт целиком, реальные списки тулов/навыков.
2. «Дублировать» пресет → визард с префиллом → переименовать → publish → агент в «Мои агенты», отдельный чат.
3. Черновик переживает kill приложения; в roster его нет.
4. В Preview запросить calendar/file write — side effect не произошёл; в Live подтвердить и отклонить risky tool.
5. Custom SKILL.md грузится только своим агентом; memory/files двух агентов взаимно изолированы; запрещённый widget отвергнут.
6. Хуки: все шесть событий в trace; журнал на экране агента.
7. PLAN-мод: план приходит PlanCard-панелью (submit_plan), «Одобрить» — typed, без сообщения «Одобряю план...»; «Поправить» создаёт версию 2 на approval.
8. GOAL: «Выполнить шаг» исполняет ровно один шаг, критерии закрываются только с evidence; required criterion без evidence держит план открытым.
9. PROGRAM-план: due-шаг будится heartbeat'ом; kill процесса во время EXECUTING → после рестарта шаг RUNNABLE, side effect не задвоен.
10. Изменить агента/план во время run: текущий run на старом снапшоте, следующий — на новом.
11. Архивировать агента: старый чат читается, новый run не стартует; его планы видны read-only.
12. Завершить `agent_blueprint` → CTA открывает builder.
13. REFLECT у custom agent с одним тулом не получает чужие тулы; CHAT ведёт себя как раньше.

- [ ] **Step 5: Финальный отчёт**

Перечислить результаты unit/build/screenshot/traxer + manual cases; подтвердить `fallbackToDestructiveMigration` (решение 2026-07-12); подтвердить, что MCP/arbitrary scripts/swarm/parallel DAG/авто-PLAN не реализовывались. Commit: `docs(agents): document builder and plan engine rails`.

## Recommended Commit Boundaries

По одному на задачу (сообщения — в шагах задач). Трейлеры слитным блоком:

```text
Tasks 3–9, 19–20, 22–23:          Trace: user-agent-builder-848153
Фаза B (automation Tasks 0–16):   Trace: structured-plan-engine-907066
Task 21:                          оба Trace-трейлера
Task 24:                          оба Trace-трейлера
Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

## Definition of Done

Builder (из старого плана, без изменений):

- Presets read-only, существующие tests зелёные; custom agent создаётся/редактируется без изменения project files; draft переживает restart и не в roster; published — в «Мои агенты», отдельный chat; runtime на immutable snapshot со scoped tools/skills/widgets; custom SKILL.md — только своему агенту; memory/files изолированы кодом; preview без side effects; risky tools через policy и approval; hooks — шесть событий без LLM/scripts/recursion; background heartbeat не ждёт UI approval; Academy capstone открывает builder.

Экран агента (новое):

- Шторки нет; `agent/{agentId}` — полноэкранный, единый скролл; препромпт разворачивается целиком; тулы/навыки/хуки — реальные списки; PRESET: Открыть чат/Дублировать; USER: + Изменить/Отключить/Архивировать; секция планов с next due.

Plan Engine:

- PLAN сохраняет typed proposal (`submit_plan`) и не получает action tools; approval — typed intent, immutable version, без magic message; GOAL исполняет approved plan по шагам с бюджетами; required criteria только через evidence; SINGLE_RUN и PROGRAM — одна модель; программа восстанавливается после restart, side effects не задваиваются; material revision всегда через approval; budget/nutrition/languages — testable templates; CHAT и REFLECT не изменились.

Инфраструктура:

- `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:verifyRoborazziDebug`, `traxer check` — успешны.
