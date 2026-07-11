# Карточка-инспектор агента (Plan C) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Новый экран — **read-mostly инспектор мини-агента**: glass-`ModalBottomSheet` поверх «Тулов», открывается тапом по `AgentCard`. Две вкладки (Обзор / Файлы), реальные данные, без редактирования.

**Architecture:** Тап по `AgentCard` ставит `selectedAgent` → открывает `AgentInspectorSheet` (M3 `ModalBottomSheet`, застеклён). Данные: `MiniAgent` (напрямую), heartbeat-статус (новый reactive-запрос `HeartbeatDao.observeActiveByAgent`), файлы агента (новый read-only `AgentFilesRepository` поверх `filesDir/agent`), через новый `AgentInspectorViewModel` (Metro, agentId подаётся flow-паттерном как `ChatViewModel.convIdFlow`). «Открыть чат» внутри листа зовёт существующий `AgentsViewModel.openAgentChat`.

**Tech Stack:** Kotlin 2.3.0, Compose (M3 `ModalBottomSheet`, experimental), Room (`HeartbeatDao`), Metro DI, glass-примитивы Plan A, Roborazzi.

## Global Constraints

- **Ветка `feature/glassmorphism` @ a5f1acf** (Plan A+B готовы, checkout уже на ней, чисто кроме чужого lessons-WIP). Исполнять прямо на checkout; если перехватят — worktree. `git add` только точные пути (без lessons-WIP).
- **Read-mostly:** ничего не редактируется/не переключается. Тумблеры хуков — статичные (не кликаются).
- **Путь≠package:** диск `com/example/…`, package `com.aiopenpocket.…`.
- **6 мини-агентов** из `MiniAgentCatalog.all` (budget/languages/tasks/reminders/nutrition/diary). Главного «AI Academy»-агента-сущности нет.
- **Модель/temperature НЕ показываем** (у `MiniAgent` их нет; глобальны — вводили бы в заблуждение). Плитки честные: **Тулов** (`toolNames.size`), **Навык** (`"load_skill" in toolNames`), **Heartbeat** (активных по agentId), **Области состояния** (`stateKeyPrefixes.size`).
- **Файлы — общая песочница** `File(application.filesDir, "agent")` (не per-agent) — с пометкой в UI. Читаем через новый repository, **НЕ через `file_tool`/`ToolExecutor`**.
- **Хуки — read-only «на будущее»:** `on_tool_call` активен (логирование/трейс реально есть), `pre_message`/`post_message`/`on_error` — «скоро», без тумблеров-переключателей.
- **Glass:** лист — `GlassSurface`/glass поверх «Тулов»; плитки/инсеты/строки — `GlassCard`. Импорты `com.aiopenpocket.ui.components.glass.*`, `ui.theme.GlassTokens`.
- **Metro-VM паттерн:** `@Inject @ContributesIntoMap(AppScope::class, binding = binding<ViewModel>()) @ViewModelKey(...)` + плейн-конструктор; в Composable `metroViewModel()`.
- **Roborazzi** sdk=[36], Pixel8, NATIVE. **Traxer** (Task 6).
- **Вне скоупа:** редактирование (препромпт/temperature/интервал), функциональные хуки, per-agent workspace/превью файлов.

## File Structure

- Modify: `agent/core/memory/dao/HeartbeatDao.kt` — новый reactive-запрос (Task 1)
- Create: `data/repository/AgentFilesRepository.kt` — read-only список файлов (Task 2)
- Create: `ui/agents/AgentInspectorViewModel.kt` — Metro-VM (Task 3)
- Create: `ui/agents/AgentInspectorSheet.kt` — glass ModalBottomSheet UI (Task 4)
- Modify: `ui/agents/AgentsScreen.kt` — точка входа (тап→sheet, «Открыть чат»); поднять хелперы до `internal` (Task 5)
- Modify: `traceability.yaml` + `@trace` — регистрация фичи (Task 6)
- Tests: `HeartbeatDaoTest` (расширить), `AgentFilesRepositoryTest`, `AgentInspectorViewModelTest`, `AgentInspectorSheetScreenshotTest`

---

### Task 1: HeartbeatDao — запрос активных heartbeat по agentId

**Files:**
- Modify: `app/src/main/java/com/example/agent/core/memory/dao/HeartbeatDao.kt`
- Test: `app/src/test/java/com/aiopenpocket/agent/core/memory/dao/HeartbeatDaoTest.kt` (существует — добавить кейс)

**Interfaces:**
- Produces: `fun HeartbeatDao.observeActiveByAgent(agentId: String): Flow<List<HeartbeatEntity>>`.

- [ ] **Step 1: Тест (RED)** — в `HeartbeatDaoTest` добавить: вставить 2 heartbeat с `agentId="budget"` (`status="scheduled"`), 1 с `agentId="diary"`, 1 с `agentId=null`; `observeActiveByAgent("budget").first()` → размер 2, все `agentId=="budget"`. Вставить `status="cancelled"` для budget → не попадает. Запустить `./gradlew :app:testDebugUnitTest --tests "*HeartbeatDaoTest*"` → FAIL (unresolved).

- [ ] **Step 2: Реализовать** — в `HeartbeatDao` добавить (импорт `kotlinx.coroutines.flow.Flow` уже есть — `observe` его использует):
```kotlin
@Query("SELECT * FROM heartbeats WHERE agentId = :agentId AND status = 'scheduled' ORDER BY createdAt DESC")
fun observeActiveByAgent(agentId: String): Flow<List<HeartbeatEntity>>
```

- [ ] **Step 3: GREEN** — тот же `--tests "*HeartbeatDaoTest*"` → PASS. Commit `feat(db): HeartbeatDao.observeActiveByAgent for agent inspector` (git add HeartbeatDao.kt + тест).

---

### Task 2: AgentFilesRepository — read-only список файлов агента

**Files:**
- Create: `app/src/main/java/com/example/data/repository/AgentFilesRepository.kt`
- Test: `app/src/test/java/com/aiopenpocket/data/repository/AgentFilesRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `data class AgentFile(val name: String, val sizeBytes: Long, val lastModified: Long)`
  - `@SingleIn(AppScope::class) class AgentFilesRepository @Inject constructor(application: Application)` с `fun list(): List<AgentFile>` (файлы из `filesDir/agent`, рекурсивно, сортировка по name).

- [ ] **Step 1: Тест (RED)** — Robolectric-тест (нужен `Application`/`filesDir`): `@RunWith(RobolectricTestRunner)`, `@Config(sdk=[36])`; в `File(app.filesDir,"agent")` создать 2 файла, вызвать `repo.list()` → 2 `AgentFile` с верными name/size; пустой/несуществующий каталог → `emptyList()`. `application` брать через `RuntimeEnvironment.getApplication()`. Run `--tests "*AgentFilesRepositoryTest*"` → FAIL.

- [ ] **Step 2: Реализовать** — создать файл:
```kotlin
package com.aiopenpocket.data.repository

import android.app.Application
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

data class AgentFile(val name: String, val sizeBytes: Long, val lastModified: Long)

/**
 * Read-only список файлов из общей песочницы агентов (File(filesDir,"agent")) — тот же каталог,
 * что пишет FileTools. Каталог ОБЩИЙ для всех агентов (не per-agent). Чтение напрямую по filesDir,
 * НЕ через ToolExecutor: UI-слой не ходит в agent-tool-слой.
 */
@SingleIn(AppScope::class)
class AgentFilesRepository @Inject constructor(private val application: Application) {
  private val sandboxDir: File get() = File(application.filesDir, "agent")

  fun list(): List<AgentFile> =
    sandboxDir.takeIf { it.isDirectory }
      ?.walkTopDown()
      ?.filter { it.isFile }
      ?.map { AgentFile(it.name, it.length(), it.lastModified()) }
      ?.sortedBy { it.name }
      ?.toList()
      .orEmpty()
}
```

- [ ] **Step 3: GREEN + commit** — тест PASS. Commit `feat(data): AgentFilesRepository (read-only agent sandbox listing)` (git add repo + тест).

---

### Task 3: AgentInspectorViewModel

**Files:**
- Create: `app/src/main/java/com/example/ui/agents/AgentInspectorViewModel.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/AgentInspectorViewModelTest.kt`

**Interfaces:**
- Consumes: `HeartbeatDao.observeActiveByAgent` (Task 1), `AgentFilesRepository` (Task 2), `MiniAgentCatalog`.
- Produces:
  - `data class AgentInspectorState(val activeHeartbeats: List<HeartbeatEntity> = emptyList(), val files: List<AgentFile> = emptyList())`
  - `class AgentInspectorViewModel(...) : ViewModel()` с `fun select(agentId: String)` и `val state: StateFlow<AgentInspectorState>`.

- [ ] **Step 1: Тест (RED)** — Robolectric; фейк/реальный `HeartbeatDao` (in-memory Room, как в HeartbeatDaoTest) + `AgentFilesRepository` на temp filesDir. `vm.select("budget")` → `state.first { it... }` содержит вставленные budget-heartbeats и файлы. `--tests "*AgentInspectorViewModelTest*"` → FAIL.

- [ ] **Step 2: Реализовать** (flow-паттерн `ChatViewModel.convIdFlow` — agentId подаётся после выбора):
```kotlin
package com.aiopenpocket.ui.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.data.repository.AgentFilesRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import androidx.lifecycle.ViewModel as AndroidxViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

data class AgentInspectorState(
  val activeHeartbeats: List<HeartbeatEntity> = emptyList(),
  val files: List<AgentFile> = emptyList(),
)

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<AndroidxViewModel>())
@ViewModelKey(AgentInspectorViewModel::class)
class AgentInspectorViewModel(
  private val heartbeatDao: HeartbeatDao,
  private val agentFilesRepository: AgentFilesRepository,
) : AndroidxViewModel() {

  private val agentIdFlow = MutableStateFlow<String?>(null)

  private val filesFlow: Flow<List<AgentFile>> = agentIdFlow
    .filterNotNull()
    .mapLatest { withContext(Dispatchers.IO) { agentFilesRepository.list() } }

  private val heartbeatsFlow: Flow<List<HeartbeatEntity>> = agentIdFlow
    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else heartbeatDao.observeActiveByAgent(id) }

  val state: StateFlow<AgentInspectorState> =
    combine(heartbeatsFlow, filesFlow.onStart { emit(emptyList()) }) { hb, files ->
      AgentInspectorState(activeHeartbeats = hb, files = files)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentInspectorState())

  /** Вызывается при открытии листа для агента; переустанавливает подписки на его данные. */
  fun select(agentId: String) { agentIdFlow.value = agentId }
}
```
> Если Metro не резолвит `binding<ViewModel>()` из-за алиаса импорта — использовать прямой импорт `androidx.lifecycle.ViewModel` без алиаса (как в `AgentsViewModel`/`ChatViewModel`); алиас в сниппете лишь чтобы избежать коллизии с `@ViewModelKey`. Сверить с реальным `AgentsViewModel.kt`.

- [ ] **Step 3: GREEN + commit** — тест PASS (whole-graph валидируется). Commit `feat(ui): AgentInspectorViewModel (heartbeat + files by agent)` (git add vm + тест).

---

### Task 4: AgentInspectorSheet — glass UI (Обзор / Файлы)

**Files:**
- Create: `app/src/main/java/com/example/ui/agents/AgentInspectorSheet.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/AgentInspectorSheetScreenshotTest.kt`

**Interfaces:**
- Consumes: `MiniAgent`, `AgentInspectorState`, glass API, `capabilityLabels`/`iconFor` (internal из AgentsScreen — Task 5 поднимает; на время Task 4 можно продублировать/использовать после Task 5).
- Produces: `@Composable fun AgentInspectorSheet(agent: MiniAgent, state: AgentInspectorState, sheetState: SheetState, onDismiss: () -> Unit, onOpenChat: () -> Unit)`.

- [ ] **Step 1: Реализовать** (образец M3 `ModalBottomSheet` — `SettingsScreen.kt:341-346`, `@OptIn(ExperimentalMaterial3Api::class)`):
  - `ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color.Transparent)` → внутри `Box(Modifier.auroraBackground())` или `GlassSurface` c контентом (лист над затемнённым фоном «Тулов»).
  - Шапка: `iconFor(agent.iconKey)` в круге, `agent.name`, под ним `agent.id`. Кнопка/действие **«Открыть чат»** → `onOpenChat()`.
  - Таб-переключатель Обзор/Файлы (`var tab by remember { mutableStateOf(Tab.Overview) }`, простой Row из двух glass-чипов).
  - **Обзор:**
    - 4 стат-плитки (`GlassCard`, read-only): **Тулов** `agent.toolNames.size`; **Навык** — `if ("load_skill" in agent.toolNames) "есть" else "—"`; **Heartbeat** — `state.activeHeartbeats.size`; **Области состояния** — `agent.stateKeyPrefixes.size`.
    - **Препромпт:** `agent.systemPrompt` в скроллящемся `GlassCard`-инсете, read-only, моноширинный/`bodySmall`.
    - **Heartbeat:** если `state.activeHeartbeats` непусто — на каждый: `title`, интервал (`intervalMinutes?` → «каждые N мин» иначе «разово»), последний (`lastRunAt?` → relative date иначе «—»), статус; иначе пустое состояние «нет активных напоминаний».
    - **Возможности:** чипсы `capabilityLabels(agent.toolNames)` (стиль `CapabilityChip`).
    - **Хуки (read-only, «на будущее»):** строки `on_tool_call` (помечен активным — логирование реально есть), `pre_message`/`post_message`/`on_error` (помечены «скоро»). Без кликабельных тумблеров — статичный индикатор.
  - **Файлы:** `state.files` → строки (имя, размер `formatBytes`, дата). Пустое состояние «файлов нет». Пометка вверху вкладки: «Общая песочница агентов (filesDir/agent)».
  - Хелпер `formatBytes(Long): String` — локальный (B/KB/MB).

- [ ] **Step 2: Скриншот-тест** — рендерить `AgentInspectorSheet` НЕ через ModalBottomSheet (он капризен под Robolectric) а через вынесенный контент-composable `AgentInspectorContent(agent, state, onOpenChat)` (выделить тело листа в отдельную `internal`-функцию, чтобы тест рендерил её напрямую поверх `auroraBackground()`); ModalBottomSheet — тонкая обёртка над ней. Baseline: `agent_inspector_overview_glass.png` (агент `budget`, с фейковым heartbeat) + `agent_inspector_files_glass.png`. `recordRoborazziDebug` → `verifyRoborazziDebug`.

- [ ] **Step 3: compile + commit** — `./gradlew :app:compileDebugKotlin` ✓. Commit `feat(ui): glass agent inspector sheet (overview + files)` (git add sheet + тест + png).

---

### Task 5: Точка входа в AgentsScreen (тап → инспектор)

**Files:**
- Modify: `app/src/main/java/com/example/ui/agents/AgentsScreen.kt`

**Interfaces:**
- Consumes: `AgentInspectorViewModel`, `AgentInspectorSheet` (Task 3/4).

- [ ] **Step 1: Поднять хелперы** — `capabilityLabels`, `iconFor` в `AgentsScreen.kt` (стр. ~183-208): `private` → `internal` (инспектор их переиспользует). `CapabilityChip` — оставить private или тоже internal, если инспектор зовёт.

- [ ] **Step 2: Встроить sheet** — в `AgentsScreen` (стр. 54-101), `@OptIn(ExperimentalMaterial3Api::class)`:
  - Рядом с VM: `val inspectorViewModel: AgentInspectorViewModel = metroViewModel()`; `var selectedAgent by remember { mutableStateOf<MiniAgent?>(null) }`; `val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)`.
  - `AgentCard(onClick = ...)` (стр. 93): заменить `viewModel.openAgentChat(...)` на `{ selectedAgent = agent; inspectorViewModel.select(agent.id) }`.
  - После `AcademyCollapsingScaffold {…}` (внутри корневого `Box`): 
    ```kotlin
    selectedAgent?.let { agent ->
      val state by inspectorViewModel.state.collectAsState()
      AgentInspectorSheet(
        agent = agent, state = state, sheetState = sheetState,
        onDismiss = { selectedAgent = null },
        onOpenChat = { val a = agent; selectedAgent = null; viewModel.openAgentChat(a.id, onOpenChat) },
      )
    }
    ```
  - `AgentsPreviewScaffold` (скриншот-двойник) — sheet НЕ добавлять (превью только каркаса списка); оставить как есть.

- [ ] **Step 3: compile + commit** — whole-app compile ✓; существующий `AgentsGlassScreenshotTest` не должен сломаться (sheet не открыт по дефолту). Commit `feat(ui): open agent inspector on Tools card tap` (git add AgentsScreen.kt).

---

### Task 6: Traxer — регистрация фичи «инспектор агента»

**Files:** Modify `traceability.yaml`, `@trace` в AgentInspectorSheet.kt + AgentInspectorViewModel.kt; re-bless затронутых.

- [ ] **Step 1: id** — `traxer id` → `agent-inspector-<hex>`.
- [ ] **Step 2: traceability.yaml** — запись: `id`, `title: "Инспектор агента (read-mostly)"`, `rationale` (ПОЧЕМУ: glass-лист поверх «Тулов» показывает реальный скоуп мини-агента — тулы/навык/heartbeat/состояние/препромпт/файлы — read-only, без редактирования; данные из MiniAgentCatalog + HeartbeatDao + filesDir; редактирование/хуки отложены). `flows: [chat]` если уместно (тап ведёт в чат).
- [ ] **Step 3: @trace** — маркер `@trace agent-inspector-<hex>` в KDoc `AgentInspectorSheet.kt` и `AgentInspectorViewModel.kt`.
- [ ] **Step 4: check + re-bless** — `traxer check`; re-bless `multi-agent-orchestration-27b8e3` (AgentsScreen изменён) после подтверждения rationale; blessing новой фичи (rationale свежий). Ожидать 0/0.
- [ ] **Step 5: commit** — `chore(traxer): register agent-inspector feature` с трейлером `Trace: agent-inspector-<hex>` (блок трейлеров слитно, без пустой строки перед Co-Authored-By).

---

## Self-Review

**1. Spec coverage** (спека, раздел «Карточка агента — read-mostly инспектор»): форма glass-sheet ✓ (T4); охват 6 мини-агентов ✓; вкладки Обзор/Файлы ✓ (T4); honest-плитки ✓ (T4, без модель/temperature); препромпт read-only ✓; heartbeat из DAO ✓ (T1+T3+T4); возможности-чипсы ✓; хуки «скоро» read-only ✓; файлы через repository не через тул ✓ (T2); точка входа тап→sheet + «Открыть чат» ✓ (T5); traxer ✓ (T6). Вне скоупа (редактирование/функц. хуки/per-agent files) — не реализуем.

**2. Placeholder scan:** `<hex>` — из `traxer id` (T6). Кода-плейсхолдеров нет; сигнатуры/пути/DI из разведки.

**3. Type consistency:** `AgentFile`(T2)→`AgentInspectorState.files`(T3)→UI(T4); `observeActiveByAgent`(T1)→VM(T3); `AgentInspectorState`/`select`(T3)→AgentsScreen(T5); glass API (Plan A) + `capabilityLabels`/`iconFor` (internal, T5).

**Открытые риски:** (a) Metro `binding<ViewModel>()` c алиасом импорта — сверить с реальным AgentsViewModel (T3 note); (b) `ModalBottomSheet` под Robolectric капризен → тест рендерит вынесенный `AgentInspectorContent`, не сам sheet (T4 Step 2); (c) agentId-flow-паттерн (не assisted-фабрика) — как ChatViewModel.

## Execution Handoff

Порядок: T1 (DAO) → T2 (repo) → T3 (VM) → T4 (sheet UI) → T5 (nav) → T6 (traxer). Бэкенд (T1-3) — TDD с юнит-тестами; UI (T4) — скриншот; каждая с ревью (T4 — риск-UI, дать ревьюер-агента), финал whole-branch. Исполнять на checkout feature/glassmorphism.
