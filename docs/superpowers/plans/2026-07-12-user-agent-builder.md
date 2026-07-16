# User Agent Builder Implementation Plan

> **SUPERSEDED 2026-07-13:** этот план слит с планом Structured Plan Engine в
> `docs/superpowers/plans/2026-07-12-agents-platform.md` — исполнять ТОТ план.
> Tasks 0–2 отсюда выполнены (коммиты `84adc0c`, `1ced5b1`, `148a847`); Tasks 3–15
> перенесены туда (3–9 с теми же номерами; 10→19, 11→20, 12→21 переписан на
> полноэкранный AgentDetailScreen вместо шторки, 13→22, 14→23, 15→24).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Дать продвинутому пользователю возможность собрать, безопасно проверить, сохранить и запустить собственного агента с prompt, scoped tools, skills, memory, widgets и декларативными lifecycle hooks.

**Architecture:** Preset `MiniAgentCatalog` остаётся read-only; custom agents, skills и hooks хранятся в Room и объединяются с presets через `AgentResolver`. Существующий `PrePromptAssembler → ReactLoopRunner → ToolExecutor` сохраняется, но получает immutable `AgentDefinition` snapshot, code-enforced `ExecutionPolicy`, per-agent memory/file namespaces и `HookEngine`.

**Tech Stack:** Kotlin 2.3, Jetpack Compose Material 3, Room, Metro DI, kotlinx.coroutines/serialization, JUnit 4, Robolectric, Compose UI Test, Roborazzi, Gradle, traxer.

## Global Constraints

- Спецификация: `docs/superpowers/specs/2026-07-12-user-agent-builder-design.md`.
- Не затирать текущие незакоммиченные изменения пользователя. Перед каждой задачей проверять `git status --short`.
- Новые Kotlin-файлы размещать под `app/src/main/java/com/aiopenpocket/...`, тесты — под `app/src/test/java/com/aiopenpocket/...`.
- Существующие файлы физически под `com/example/` не переносить; их package уже `com.aiopenpocket.*`.
- Не переносить preset agents в Room и не разрешать их редактирование; разрешено только клонирование.
- Не исполнять пользовательский JavaScript/Kotlin и не делать LLM-вызовы из hooks.
- `system_base.md` остаётся immutable и всегда имеет приоритет над user agent prompt/skills.
- MCP runtime, workflow graph, swarm, marketplace, cloud sync и arbitrary scripts вне scope.
- Миграции БД: решение пользователя 2026-07-12 — остаёмся на `fallbackToDestructiveMigration` (потеря локальных данных, включая первых custom agents, при schema bump допустима до релиза). Явные `Migration`, schema export и migration tests НЕ добавлять.
- Preview обязан блокировать `WRITE`, `DESTRUCTIVE`, `EXTERNAL` в коде до `ToolExecutor`.
- Custom memory и files обязаны быть изолированы по `agentId`; prompt-инструкция не считается enforcement.
- REFLECT не должен обходить allowlist: сегодня `ReflectionOrchestrator` отдаёт модели `registry.getAllSchemas()` без agent — для custom agents рефлексия обязана использовать scoped tools/skills (закрывается в Task 4).
- `DRAFT` не появляется в roster; удаление из UI переводит agent в `ARCHIVED`.
- Старый `docs/superpowers/specs/2026-07-04-agent-hooks-design.md` не реализовывать параллельно: его LLM-hook model несовместима с утверждённым декларативным `HookEngine`.
- Trace ID фичи уже заморожен: `user-agent-builder-848153`. Task 0 добавляет запись в `traceability.yaml` и `@trace` на domain/repository/builder entry points; повторно `traxer id` не запускать.
- Перед каждым коммитом запускать `traxer check`; stale warning bless-ить только после подтверждения rationale.
- Трейлеры коммитов (`Trace:`, `Co-Authored-By:`) писать одним слитным блоком без пустой строки между ними — иначе git не парсит их как trailers.
- Коммиты ниже — рекомендуемые границы. Не создавать commits без явного разрешения пользователя.

## File Map

Новые production-файлы:

```text
app/src/main/java/com/aiopenpocket/agent/core/agent/
  AgentDefinition.kt
  AgentResolver.kt
  AgentValidator.kt
  UserAgentEntity.kt
  UserAgentDao.kt
  UserAgentRepository.kt
  AgentRoomConverters.kt
app/src/main/java/com/aiopenpocket/agent/core/skill/
  UserSkillEntity.kt
  UserSkillDao.kt
  UserSkillRepository.kt
app/src/main/java/com/aiopenpocket/agent/core/policy/
  ExecutionPolicy.kt
  ToolApprovalCoordinator.kt
app/src/main/java/com/aiopenpocket/agent/core/hook/
  AgentHook.kt
  AgentHookEntity.kt
  AgentHookDao.kt
  AgentHookRepository.kt
  HookEngine.kt
app/src/main/java/com/aiopenpocket/ui/agents/builder/
  AgentBuilderRoute.kt
  AgentBuilderScreen.kt
  AgentBuilderViewModel.kt
  AgentBuilderUiState.kt
  AgentBuilderValidator.kt
  components/*.kt
```

Основные изменяемые seams:

```text
app/src/main/java/com/example/agent/core/agent/MiniAgent.kt
app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt
app/src/main/java/com/example/agent/core/skill/SkillRegistry.kt
app/src/main/java/com/example/agent/core/skill/LoadSkillTool.kt
app/src/main/java/com/example/agent/loop/AgentLoopOrchestrator.kt
app/src/main/java/com/example/agent/loop/ReflectionOrchestrator.kt
app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt
app/src/main/java/com/example/agent/loop/TurnContextHolder.kt
app/src/main/java/com/example/agent/loop/TurnManager.kt
app/src/main/java/com/example/agent/loop/TurnPorts.kt
app/src/main/java/com/example/di/TurnModule.kt
app/src/main/java/com/example/agent/tools/MemoryTools.kt
app/src/main/java/com/example/agent/tools/FileTools.kt
app/src/main/java/com/example/agent/tools/RenderCardTool.kt
app/src/main/java/com/example/data/database/AppDatabase.kt
app/src/main/java/com/example/data/repository/AgentFilesRepository.kt
app/src/main/java/com/example/di/DatabaseBindings.kt
app/src/main/java/com/example/ui/agents/*
app/src/main/java/com/example/ui/chat/*
app/src/main/java/com/example/MainActivity.kt
app/src/main/assets/lessons/agent_blueprint.json
traceability.yaml
AGENTS.md
```

---

### Task 0: Зафиксировать baseline и traceability

**Files:**
- Modify: `traceability.yaml`
- Modify: будущие feature entry points по мере их появления.

**Interfaces:**
- Consumes: текущий working tree и `traxer`.
- Produces: новый immutable trace ID, используемый всеми задачами builder.

- [ ] **Step 1: Зафиксировать исходное состояние**

Run:

```powershell
git branch --show-current
git status --short
.\gradlew.bat :app:testDebugUnitTest
traxer check
```

Expected: состояние и исходные failures записаны; никаких reset/checkout/clean.

- [ ] **Step 2: Зарегистрировать feature ID**

ID уже создан и заморожен: `user-agent-builder-848153` — повторно `traxer id` НЕ
запускать (id обязан остаться стабильным между сессиями). Добавить в
`traceability.yaml`:

```yaml
  - id: user-agent-builder-848153
    title: "User agent builder"
    rationale: "Замкнуть обучение AI Academy в пользовательский результат: безопасно собрать, проверить, сохранить и запустить собственного scoped агента без изменения кода."
    flows: [chat]
```

- [ ] **Step 3: Проверить реестр**

Run:

```powershell
traxer check
```

Expected: новая feature пока может быть помечена «без кода»; это закрывается
маркером в Task 1.

---

### Task 1: Ввести AgentDefinition, validator и preset adapter

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/agent/AgentDefinition.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/agent/AgentValidator.kt`
- Modify: `app/src/main/java/com/example/agent/core/agent/MiniAgent.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/agent/AgentDefinitionTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/agent/AgentValidatorTest.kt`

**Interfaces:**
- Produces: `AgentDefinition`, `AgentMemoryPolicy`, `AgentStatus`,
  `MiniAgent.toDefinition()`, `AgentValidator.validate(definition)`.

- [ ] **Step 1: Написать failing domain tests**

Проверить:

```kotlin
assertEquals(AgentSource.PRESET, MiniAgentCatalog.get("budget")!!.toDefinition().source)
assertTrue(validator.validate(validDefinition).isEmpty())
assertTrue(validator.validate(validDefinition.copy(id = "budget")).any { it.code == "preset_id_collision" })
assertTrue(validator.validate(validDefinition.copy(defaultMode = AgentMode.GOAL, allowedModes = setOf(AgentMode.CHAT))).any())
assertTrue(validator.validate(validDefinition.copy(memoryPolicy = AgentMemoryPolicy("../x", false))).any())
```

- [ ] **Step 2: Подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.AgentDefinitionTest" --tests "com.aiopenpocket.agent.core.agent.AgentValidatorTest"
```

Expected: FAIL — типов ещё нет.

- [ ] **Step 3: Добавить минимальную модель**

```kotlin
enum class AgentSource { PRESET, USER }
enum class AgentStatus { DRAFT, PUBLISHED, ARCHIVED }

data class AgentMemoryPolicy(
  val namespace: String,
  val includeSharedProfile: Boolean,
)

data class AgentDefinition(
  val id: String,
  val source: AgentSource,
  val name: String,
  val description: String,
  val iconKey: String,
  val systemPrompt: String,
  val toolNames: Set<String>,
  val skillIds: Set<String>,
  val defaultMode: AgentMode,
  val allowedModes: Set<AgentMode>,
  val memoryPolicy: AgentMemoryPolicy,
  val allowedWidgetTemplates: Set<String>,
  val hooks: List<AgentHook> = emptyList(),
  val configVersion: Long,
  val status: AgentStatus,
) {
  val enabled: Boolean get() = status == AgentStatus.PUBLISHED
}
```

До Task 8 создать временный domain `AgentHook` с окончательной сигнатурой, но
пустым списком у preset adapter. Не создавать placeholder behavior.

- [ ] **Step 4: Реализовать validator и preset adapter**

Validator проверяет non-blank поля, ID regex `[a-z0-9][a-z0-9_-]{2,63}`,
preset collision, default mode membership, namespace
`agent:$id:`, известные tools/skills/widgets и лимиты prompt/skill. Конкретные
лимиты первой версии:

```kotlin
const val MAX_SYSTEM_PROMPT_CHARS = 20_000
const val MAX_AGENT_NAME_CHARS = 60
const val MAX_DESCRIPTION_CHARS = 240
const val MAX_CUSTOM_SKILL_CHARS = 40_000
```

Preset adapter выводит `skillIds` явно:

```kotlin
val skillIds = when (id) {
  "budget", "languages", "diary" -> setOf(id)
  else -> emptySet()
}
```

Маппинг сверен с фактическим кодом (2026-07-12): skills в Metro =
{budget, diary, fitness, languages}, агенты каталога = {budget, languages,
tasks, reminders, nutrition, diary}; пересечение id — ровно
budget/languages/diary. Навык `fitness` существует без одноимённого агента
(доступен plain chat) — это нормально. Закрепить маппинг тестом против реальных
id из SkillModule, чтобы дрейф каталога ронял тест, а не молча расходился.

- [ ] **Step 5: Запустить tests и trace**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.*"
traxer check
```

Expected: PASS; feature связана с `@trace`.

---

### Task 2: Добавить Room persistence и транзакционный repository

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/agent/AgentRoomConverters.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/agent/UserAgentEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/agent/UserAgentDao.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/agent/UserAgentRepository.kt`
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/di/DatabaseBindings.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/agent/UserAgentRepositoryTest.kt`

**Interfaces:**
- Consumes: Task 1 domain model.
- Produces: `observePublished()`, `get(id)`, `saveDraft()`, `publish()`,
  `archive()`, `clonePreset()`.

- [ ] **Step 1: Написать failing repository tests**

In-memory Room tests должны доказать:

```kotlin
repository.saveDraft(draft)
assertTrue(repository.observePublished().first().isEmpty())
val published = repository.publish(draft.id)
assertEquals(AgentStatus.PUBLISHED, published.status)
assertEquals(1L, published.configVersion)
repository.archive(draft.id)
assertNotNull(repository.get(draft.id)) // archived definition survives
```

Также проверить rollback, если child data невалидна.

- [ ] **Step 2: Подтвердить RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.UserAgentRepositoryTest"
```

Expected: FAIL.

- [ ] **Step 3: Добавить entity/DAO/converters**

Коллекции хранить JSON-строками через один `@TypeConverter` с
`kotlinx.serialization`; enums — `.name`.

```kotlin
@Dao
interface UserAgentDao {
  @Query("SELECT * FROM user_agents WHERE id = :id")
  suspend fun get(id: String): UserAgentEntity?

  @Query("SELECT * FROM user_agents WHERE status = 'PUBLISHED' ORDER BY updatedAt DESC")
  fun observePublished(): Flow<List<UserAgentEntity>>

  @Upsert suspend fun upsert(entity: UserAgentEntity)

  @Query("UPDATE user_agents SET status = :status, updatedAt = :now WHERE id = :id")
  suspend fun updateStatus(id: String, status: String, now: Long)
}
```

- [ ] **Step 4: Реализовать repository transaction**

```kotlin
@Transaction
suspend fun publish(id: String): AgentDefinition {
  val draft = requireNotNull(dao.get(id))
  validator.requirePublishable(draft.toDefinition())
  val published = draft.copy(
    status = AgentStatus.PUBLISHED.name,
    configVersion = draft.configVersion + 1,
    updatedAt = clock(),
  )
  dao.upsert(published)
  return published.toDefinition()
}
```

Transaction custom skills/hooks расширяется в Tasks 4 и 8.

- [ ] **Step 5: Поднять DB version (без миграций — destructive fallback)**

Добавить entity/DAO в `AppDatabase` и поднять `version` на +1 от фактической
закоммиченной версии на момент реализации (НЕ хардкодить номер из плана: в
рабочем дереве параллельно идёт bump 1→2 из conversation-работы — взять
актуальное значение из кода). Явные `Migration`, schema export и migration
tests НЕ добавлять: действует `fallbackToDestructiveMigration` — решение
пользователя 2026-07-12, потеря локальных custom agents при schema bump
допустима до релиза. Обновить устаревший комментарий в `DatabaseBindings`,
чтобы он честно описывал политику и текущую версию.

- [ ] **Step 6: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.UserAgentRepositoryTest"
.\gradlew.bat :app:assembleDebug
```

Expected: PASS / `BUILD SUCCESSFUL`.

---

### Task 3: Ввести AgentResolver и убрать runtime-зависимость от каталога

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/agent/AgentResolver.kt`
- Modify: `app/src/main/java/com/example/agent/core/prompt/PrePromptAssembler.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentsViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentsScreen.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentInspectorSheet.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt`
- Modify: `app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/agent/AgentResolverTest.kt`

**Interfaces:**
- Consumes: preset adapter + `UserAgentRepository`.
- Produces: single resolution API for foreground, background and UI.

- [ ] **Step 1: Написать failing resolver tests**

```kotlin
assertEquals(AgentSource.PRESET, resolver.resolve("budget")!!.source)
assertEquals(AgentSource.USER, resolver.resolve(custom.id)!!.source)
assertNull(resolver.resolve("missing"))
assertEquals(listOf("budget", custom.id), resolver.observeRoster().first().map { it.id })
```

Отдельно проверить, что archived/draft отсутствуют в roster и preset имеет
приоритет при collision.

- [ ] **Step 2: Реализовать resolver**

```kotlin
interface AgentResolver {
  suspend fun resolve(id: String?): AgentDefinition?
  fun observeRoster(): Flow<List<AgentDefinition>>
}
```

`observeRoster()` объединяет immutable preset list и published Room flow.
Preset-часть статична; `resolve()` в горячем пути хода — один DAO-запрос на
ход, это приемлемо. Повторных resolve ниже по стеку не делать (см. Step 3 и
Task 5).

- [ ] **Step 3: Перевести PrePromptAssembler**

`assemble()` уже suspend, поэтому:

```kotlin
val agent = agentResolver.resolve(conversation?.agentId)
val skillIds = agent?.skillIds
val toolNames = agent?.toolNames
```

Prompt обязан сохранять порядок из спецификации, НЕ теряя существующие секции
assembler (язык, персона/роль агента, tutorial-промпт, `<user_data>`-фенсинг
недоверенных данных). Shared profile добавлять только если
`memoryPolicy.includeSharedProfile`.

`PromptContext` расширяется полем `agent: AgentDefinition?` — resolve происходит
ровно один раз здесь; оркестратор передаёт snapshot дальше (в
`TurnContextHolder` и `ReactLoopRunner`) без повторного обращения к resolver.

- [ ] **Step 4: Перевести остальные direct references**

Поиск:

```powershell
rg "MiniAgentCatalog\.(get|all)" app/src/main/java
```

Expected после изменения: direct references остаются только в preset adapter и
preview/test fixtures.

- [ ] **Step 5: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.agent.*" --tests "com.aiopenpocket.agent.core.prompt.*" --tests "com.aiopenpocket.agent.workers.HeartbeatRunnerTest"
```

Expected: PASS.

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
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/di/DatabaseBindings.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/skill/CompositeSkillRegistryTest.kt`

**Interfaces:**
- Produces: suspend `get`, `catalog`, `ids`; explicit allowed skill IDs per run.

- [ ] **Step 1: Написать failing tests**

Проверить built-in lookup, Room lookup, duplicate ID rejection, owner isolation,
size limit и запрет загрузки custom skill другого агента.

- [ ] **Step 2: Изменить registry contract**

```kotlin
suspend fun get(id: String): Skill?
suspend fun ids(): Set<String>
suspend fun catalog(allowedIds: Set<String>? = null): String
```

Built-in map остаётся из Metro; Room repository добавляется вторым источником.
При collision built-in побеждает, а validator запрещает публикацию collision.

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

Сегодня `ReflectionOrchestrator` вызывает `reactLoopRunner.run(...,
registry.getAllSchemas(), ...)` и не передаёт agent: custom agent в режиме
рефлексии получил бы ПОЛНЫЙ набор тулов и скиллов мимо allowlist. Передать в
draft/revise фазы scoped-схемы (`context.toolNames` из PromptContext) и
`agent`-снапшот — тем же способом, что в `AgentLoopOrchestrator`. Тест: у custom
agent с одним разрешённым тулом REFLECT-ход не может вызвать чужой тул
(`Tool ... is not available in this context`).

- [ ] **Step 5: Включить custom skills в publish transaction**

Agent draft, skills и hooks должны записываться атомарно. При invalid Markdown
transaction откатывается.

- [ ] **Step 6: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.skill.*" --tests "com.aiopenpocket.agent.loop.*"
```

Expected: PASS.

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
- Consumes: resolved agent snapshot.
- Produces: code-enforced writes/search scoped to `agent:{id}:`.

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

Не резолвить agent повторно внутри tools: snapshot кладёт оркестратор из
`PromptContext.agent` (single resolve per turn, см. Task 3 Step 3).

- [ ] **Step 3: Добавить scoped repository methods**

```kotlin
suspend fun searchFactsInNamespace(namespace: String, query: String): List<FactEntity>
suspend fun getFactsByNamespace(namespace: String): List<FactEntity>
```

DAO query должна включать `key LIKE :namespace || '%'`.

- [ ] **Step 4: Enforce MemoryTools**

Custom agent:

```kotlin
val storedKey = context.agent?.memoryPolicy?.namespace?.plus(userKey) ?: userKey
```

Удалить из custom path право модели назначать `profile`/`preference`; category
принудительно `general`. Shared profile только читается assembler.

- [ ] **Step 5: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.tools.MemoryToolsNamespaceTest" --tests "com.aiopenpocket.agent.core.prompt.*"
```

Expected: PASS.

---

### Task 6: Enforce per-agent file sandbox

**Files:**
- Modify: `app/src/main/java/com/example/agent/tools/FileTools.kt`
- Modify: `app/src/main/java/com/example/data/repository/AgentFilesRepository.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentInspectorViewModel.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/tools/FileToolsSandboxTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/data/repository/AgentFilesRepositoryTest.kt`

**Interfaces:**
- Produces: root `filesDir/agents/{agentId}` for custom agents.

- [ ] **Step 1: Написать failing isolation tests**

Создать `agents/a/private.txt` и `agents/b/private.txt`; agent A видит только A.
Проверить `../b/private.txt`, absolute path, mixed `\`/`/` и canonical traversal.

- [ ] **Step 2: Вынести root resolver**

```kotlin
internal fun sandboxRoot(filesDir: File, agentId: String?): File =
  if (agentId == null) File(filesDir, "agent")
  else File(File(filesDir, "agents"), agentId)
```

Custom ID уже прошёл regex validator.

- [ ] **Step 3: Использовать TurnContextHolder в FileTools**

Все операции одного вызова используют один snapshot root. Не вычислять root
повторно после suspension.

- [ ] **Step 4: Исправить Inspector repository**

```kotlin
suspend fun list(agentId: String): List<AgentFile>
```

Удалить текст UI «общая песочница».

- [ ] **Step 5: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.tools.FileToolsSandboxTest" --tests "com.aiopenpocket.data.repository.AgentFilesRepositoryTest"
```

Expected: PASS.

---

### Task 7: Добавить widget allowlist

**Files:**
- Modify: `app/src/main/java/com/example/agent/tools/RenderCardTool.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/tools/RenderCardPolicyTest.kt`

**Interfaces:**
- Produces: runtime rejection запрещённых templates.

- [ ] **Step 1: Написать failing tests**

Проверить allowed `todo_list`, denied `lesson_quiz`, unknown template и plain
chat behavior.

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

`RenderCardTool` читает текущий agent snapshot из `TurnContextHolder` и возвращает
non-recoverable `ToolResult.Error` до формирования card JSON.

- [ ] **Step 4: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.tools.RenderCardPolicyTest" --tests "com.aiopenpocket.ui.chat.DrivenUiEngineTest"
```

Expected: PASS.

---

### Task 8: Реализовать declarative HookEngine

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHook.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookEntity.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookDao.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/AgentHookRepository.kt`
- Create: `app/src/main/java/com/aiopenpocket/agent/core/hook/HookEngine.kt`
- Modify: `app/src/main/java/com/example/data/database/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/di/DatabaseBindings.kt`
- Modify: `app/src/main/java/com/example/agent/loop/ReactLoopRunner.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/hook/HookEngineTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/ReactLoopHooksTest.kt`

**Interfaces:**
- Produces: deterministic hook decisions at six lifecycle points.

- [ ] **Step 1: Написать pure HookEngine tests**

Проверить ordering `(priority, id)`, action/event compatibility, `DENY > ALLOW`,
condition mismatch, execution limit, `ON_ERROR` non-recursion.

- [ ] **Step 2: Зафиксировать модели**

```kotlin
enum class HookEvent { BEFORE_TURN, AFTER_TURN, BEFORE_TOOL, AFTER_TOOL, ON_ERROR, ON_COMPLETE }
enum class HookActionType { ADD_INSTRUCTION, REQUIRE_APPROVAL, ALLOW, DENY, LOG, STOP }

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

Перед каждой `streamChat` выполнить `BEFORE_TURN`; после collect —
`AFTER_TURN`; вокруг каждого tool — `BEFORE_TOOL/AFTER_TOOL`; перед error return —
`ON_ERROR`; перед successful return — `ON_COMPLETE`.

`ADD_INSTRUCTION` добавляет ephemeral system message только для текущей
итерации и удаляет его после неё. ВАЖНО: вставлять/удалять ТОЛЬКО в голове
списка (сразу после базового system-сообщения), а не в хвост — `messages`
мутируется in-place, и в хвосте живут пары assistant(tool_calls)/tool; разрыв
пары даёт HTTP 400 у строгих провайдеров. Hook никогда не вызывает LLM/tool.

- [ ] **Step 5: Добавить persistence и журнал**

Хранить последние 100 run records на hook; cleanup выполнять после insert.
Repository возвращает hooks уже отсортированными.

- [ ] **Step 6: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.hook.*" --tests "com.aiopenpocket.agent.loop.ReactLoopHooksTest"
```

Expected: PASS.

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
- Modify: mixed-action tools in `app/src/main/java/com/example/agent/tools/`
- Modify: `app/src/main/java/com/example/agent/loop/AgentLoopEvent.kt` if events are split; otherwise `AgentLoopOrchestrator.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatIntent.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatUiState.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/policy/ExecutionPolicyTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/loop/ToolApprovalFlowTest.kt`

**Interfaces:**
- Consumes: tool `RiskClass` + `BEFORE_TOOL` decision.
- Produces: execute/simulate/deny/await-approval before `ToolExecutor`.

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

Добавить table-driven tests на каждую mapping. Не определять risk по
непроверенной подстроке SQL: переиспользовать существующий SQL parser/validator.

- [ ] **Step 4: Реализовать coordinator**

Singleton coordinator хранит `CompletableDeferred<Boolean>` по уникальному
`requestId`, публикует pending requests через `StateFlow`, удаляет entry при
resolve/cancel. Нельзя подтверждать request другого run. Approval без ответа =
deny: таймаут (например, 120 с) и уход приложения в фон резолвят deferred в
`false` (recoverable policy denial). Ходы живут в appScope-`TurnManager` и
переживают экран — pending approval не имеет права подвесить фоновый ход
навечно.

- [ ] **Step 5: Встроить gate до ToolExecutor**

Последовательность обязана быть:

```text
schema allowlist → skill/widget checks → BEFORE_TOOL → ExecutionPolicy →
optional approval → ToolExecutor
```

Simulation возвращает модели JSON:

```json
{"status":"preview_only","executed":false,"tool":"calendar","arguments":{}}
```

- [ ] **Step 6: Пробросить ExecutionMode через TurnManager**

`ExecutionMode` (LIVE по умолчанию, PREVIEW из builder-теста) должен доехать до
policy через существующий тракт фоновых ходов: `TurnManager.launchTurn(...)` →
порт `TurnExecutor.execute(...)` (`TurnPorts.kt`, диспатч в `TurnModule`) →
`AgentLoopOrchestrator.execute(...)` → `ReactLoopRunner`. Сохранить mode в
`TurnSession` рядом с retry-контекстом, чтобы «Повторить» переигрывал ход в том
же режиме. НЕ заводить параллельный вход для preview — builder-тест использует
тот же TurnManager (отдельная test conversation, см. Task 11).

- [ ] **Step 7: Добавить Chat UI**

Добавить `AgentEvent.ToolApprovalRequired`, state `pendingToolApproval`,
intents approve/deny и modal/bar с tool name, risk, arguments. Убрать существующее
ограничение, из-за которого approval UI показывается только при `agentId == null`.

- [ ] **Step 8: Проверить cancellation**

Cancel run должен завершить pending deferred, убрать UI и не выполнить tool.
Background heartbeat никогда не ждёт UI: approval decision превращается в deny.

- [ ] **Step 9: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.policy.*" --tests "com.aiopenpocket.agent.loop.ToolApprovalFlowTest"
```

Expected: PASS.

---

### Task 10: Реализовать builder state, validation и publish ViewModel

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderUiState.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderValidator.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderViewModel.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/builder/AgentBuilderViewModelTest.kt`

**Interfaces:**
- Consumes: repositories, resolver, tool/skill registries, validator.
- Produces: six-step draft state, autosave, preview launch, publish.

- [ ] **Step 1: Написать failing ViewModel tests**

Проверить new draft, clone preset, autosave debounce, validation per step,
publish only valid, archive, edit published creates new configVersion, restoration
after ViewModel recreation.

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

Изменения draft идут через `MutableStateFlow`, `debounce(500)` и repository
transaction. Ошибка сохранения остаётся в state и не теряет in-memory draft.

- [ ] **Step 4: Реализовать clone/publish**

Clone создаёт новый UUID/slug ID, source USER, namespace по новому ID, status
DRAFT. Publish вызывает единый domain validator и repository transaction.

- [ ] **Step 5: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.agents.builder.AgentBuilderViewModelTest"
```

Expected: PASS.

---

### Task 11: Построить six-step Compose wizard и navigation

**Files:**
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderRoute.kt`
- Create: `app/src/main/java/com/aiopenpocket/ui/agents/builder/AgentBuilderScreen.kt`
- Create: focused components under `app/src/main/java/com/aiopenpocket/ui/agents/builder/components/`
- Modify: `app/src/main/java/com/example/MainActivity.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/builder/AgentBuilderScreenTest.kt`

**Interfaces:**
- Consumes: Task 10 ViewModel.
- Produces: routes `agent_builder` and `agent_builder/{agentId}`.

- [ ] **Step 1: Написать Compose tests**

Проверить testTags:

```text
agent_builder_next
agent_builder_back
agent_builder_tool_<name>
agent_builder_skill_<id>
agent_builder_hook_add
agent_builder_preview
agent_builder_live_test
agent_builder_publish
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

Разнести по небольшим composables: Identity, Instructions, Capabilities,
LoopModes, Hooks, TestAndPublish. Переиспользовать onboarding progress pattern,
не копировать весь `OnboardingScreen`.

- [ ] **Step 4: Реализовать risk rails**

Tool row показывает `riskClass`, permission и consequence. Первый выбор
`WRITE/DESTRUCTIVE/EXTERNAL` открывает consent; отказ не включает tool.
`database_query` и `sms` скрыты из default presets builder, но могут быть
показаны в explicit «Advanced tools» с отдельным предупреждением.

- [ ] **Step 5: Реализовать custom skill/hook editors**

Skill editor показывает char count и manifest fields. Hook editor использует
только event-compatible actions; raw JSON пользователю не показывать.

- [ ] **Step 6: Добавить Preview/Live test**

Preview создаёт/использует отдельную test conversation и запускает существующий
TurnManager с `ExecutionMode.PREVIEW`. Live test требует явного переключения.

- [ ] **Step 7: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.agents.builder.AgentBuilderScreenTest"
.\gradlew.bat :app:verifyRoborazziDebug
```

Expected: PASS; если screenshot baseline намеренно изменился, обновлять его только
после визуального review.

---

### Task 12: Интегрировать roster и реальный Inspector

**Files:**
- Modify: `app/src/main/java/com/example/ui/agents/AgentsViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentsScreen.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentInspectorViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/agents/AgentInspectorSheet.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/AgentsViewModelTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/AgentInspectorViewModelTest.kt`
- Test: existing Roborazzi agent screenshots.

**Interfaces:**
- Consumes: resolver + builder routes.
- Produces: «Шаблоны» и «Мои агенты», edit/clone/archive actions.

- [ ] **Step 1: Написать failing roster tests**

Проверить deterministic sections, no drafts/archived, open chat creates
conversation with agent ID, preset edit unavailable, custom edit available.

- [ ] **Step 2: Перевести roster на Flow**

`AgentsViewModel` собирает `AgentResolver.observeRoster()` и разделяет по
`source`. FAB вызывает `onCreateAgent`.

- [ ] **Step 3: Заменить Inspector placeholders**

Показывать реальные prompt/tools/skills/memory/hooks/files/configVersion.
Удалить fake hooks. Actions:

```text
PRESET: Open chat, Duplicate
USER: Open chat, Edit, Duplicate, Disable/Enable, Archive
```

- [ ] **Step 4: Запустить tests/screenshots**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.ui.agents.*"
.\gradlew.bat :app:verifyRoborazziDebug
```

Expected: PASS.

---

### Task 13: Поддержать custom agents в heartbeat

**Files:**
- Modify: `app/src/main/java/com/example/agent/workers/HeartbeatRunner.kt`
- Modify: heartbeat creation validation in `app/src/main/java/com/example/agent/tools/HeartbeatTool.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/workers/HeartbeatRunnerTest.kt`

**Interfaces:**
- Consumes: `AgentResolver`, `ExecutionPolicy`.
- Produces: safe background custom-agent snapshot.

- [ ] **Step 1: Написать failing tests**

Проверить published custom agent, archived rejection, tool intersection,
approval-required denial, config snapshot and missing agent.

- [ ] **Step 2: Resolve agent через AgentResolver**

```kotlin
val agent = resolver.resolve(heartbeat.agentId)
  ?: return HeartbeatRunResult.Skipped("Agent not found")
if (!agent.enabled) return HeartbeatRunResult.Skipped("Agent is not published")
```

- [ ] **Step 3: Ограничить tools**

```kotlin
val tools = agent.toolNames intersect SAFE_TOOL_NAMES
```

Затем применить policy. Background никогда не создаёт pending UI approval.

- [ ] **Step 4: Запустить tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.workers.HeartbeatRunnerTest"
```

Expected: PASS.

---

### Task 14: Добавить Academy → Builder bridge

**Files:**
- Modify: `app/src/main/assets/lessons/agent_blueprint.json`
- Modify: `app/src/main/java/com/example/agent/core/lesson/LessonChatController.kt`
- Modify: `app/src/main/java/com/example/ui/chat/DrivenUiEngine.kt`
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt`
- Modify: navigation callback in `app/src/main/java/com/example/MainActivity.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/lesson/LessonAssetsValidationTest.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/chat/DrivenUiEngineTest.kt`

**Interfaces:**
- Consumes: completed `agent_blueprint` lesson.
- Produces: CTA `Создать агента` → new draft with source `ACADEMY_CAPSTONE`.

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

Parser должен отвергать unknown action types.

- [ ] **Step 2: Добавить CTA только для capstone**

Обычные lessons сохраняют старое completion behavior. CTA показывается после
реального завершения `agent_blueprint`, а не при ручном открытии card JSON.

- [ ] **Step 3: Передать source metadata**

Первая версия открывает пустой draft с `sourceContext = ACADEMY_CAPSTONE`; не
парсить свободные сообщения ученика через LLM.

- [ ] **Step 4: Запустить lesson/chat tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.aiopenpocket.agent.core.lesson.LessonAssetsValidationTest" --tests "com.aiopenpocket.ui.chat.DrivenUiEngineTest"
```

Expected: PASS.

---

### Task 15: Документация, security regression и финальная верификация

**Files:**
- Modify: `AGENTS.md`
- Modify: `traceability.yaml`
- Modify: relevant tests from Tasks 1–14.
- Create: `app/src/test/java/com/aiopenpocket/agent/security/UserAgentIsolationTest.kt`

**Interfaces:**
- Produces: проверяемый end-to-end Definition of Done.

- [ ] **Step 1: Добавить consolidated security tests**

Один suite обязан проверить:

```text
hallucinated tool outside allowlist
custom skill outside skillIds
foreign memory namespace
foreign file sandbox
forged widget template
preview side effect
denied approval
recursive ON_ERROR
edit config during run
background approval
```

- [ ] **Step 2: Обновить документацию**

Исправить фактические DB name/version, описать custom agents, composite skills,
declarative hooks, preview/live, namespaces и MCP как future extension.
Старую hook spec пометить superseded ссылкой на новую design spec; не удалять
исторический документ.

- [ ] **Step 3: Запустить полный unit suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Собрать APK и проверить screenshots**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:verifyRoborazziDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Проверить traceability**

Run:

```powershell
traxer check
```

Expected: no broken links/features without code; stale warnings разобраны, а не
автоматически blessed.

- [ ] **Step 6: Выполнить manual smoke test**

1. Создать draft, закрыть app, продолжить draft.
2. Клонировать preset, изменить prompt, опубликовать.
3. Убедиться, что agent появился в «Мои агенты» и открыл отдельный chat.
4. В Preview запросить calendar/file write: side effect не произошёл.
5. В Live подтвердить и отклонить risky tool.
6. Создать custom SKILL.md и убедиться, что другой agent не может его загрузить.
7. Записать memory/file двумя agents и проверить взаимную изоляцию.
8. Проверить allowed и denied widgets.
9. Проверить все шесть hook events в trace.
10. Изменить agent во время streaming: текущий run использует старую version,
    следующий — новую.
11. Архивировать agent: старый chat читается, новый run не запускается.
12. Завершить `agent_blueprint` и открыть builder по CTA.
13. Запустить heartbeat custom agent без approval wait.
14. Включить REFLECT у custom agent с одним разрешённым tool и убедиться, что
    рефлексия не получает тулы вне allowlist.

- [ ] **Step 7: Финальный отчёт**

Отчёт должен перечислить результаты unit/build/screenshot/traxer, manual cases,
подтвердить действующую политику `fallbackToDestructiveMigration` (явных
миграций нет — решение пользователя 2026-07-12) и явно подтвердить, что
MCP/arbitrary scripts/swarm не реализовывались.

## Recommended Commit Boundaries

1. `feat(agents): define user agent domain contract`
2. `feat(agents): persist custom agent drafts`
3. `refactor(agents): resolve preset and custom definitions`
4. `feat(skills): load agent-owned Room skills`
5. `feat(memory): isolate custom agent facts`
6. `feat(files): isolate custom agent sandboxes`
7. `feat(cards): enforce agent widget allowlists`
8. `feat(hooks): add declarative lifecycle engine`
9. `feat(tools): enforce preview and approval policy`
10. `feat(agents): add builder state and validation`
11. `feat(agents): add six-step builder UI`
12. `feat(agents): integrate custom roster and inspector`
13. `feat(heartbeat): run published custom agents safely`
14. `feat(academy): connect capstone to agent builder`
15. `docs(agents): document builder rails and verification`

Каждый commit message при реальном выполнении получает trailer — слитным блоком
с `Co-Authored-By`, без пустой строки между трейлерами (иначе git не распарсит
их как trailers):

```text
Trace: user-agent-builder-848153
```

## Definition of Done

- Presets остаются read-only и продолжают проходить существующие tests.
- Custom agent создаётся и редактируется без изменения project files.
- Draft переживает restart, но не показывается в roster.
- Published agent появляется в «Мои агенты» и открывает отдельный chat.
- Runtime использует immutable config snapshot и explicit tool/skill/widget scope.
- Custom SKILL.md доступен только разрешившему его agent.
- Memory и files изолированы по agent ID кодом.
- Preview не выполняет side effects.
- Live risky tools проходят policy и user approval.
- Hooks поддерживают шесть событий без LLM/scripts/recursion.
- Background heartbeat не ждёт UI approval.
- Inspector показывает реальную конфигурацию и hook log.
- Academy capstone открывает builder.
- MCP, swarm и arbitrary code остаются вне реализации.
- `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:verifyRoborazziDebug` и
  `traxer check` успешны.
