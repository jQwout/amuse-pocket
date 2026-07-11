# Glass-рестайл 5 зон (Plan B) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перевести 5 UI-зон (нижняя навигация, Home, Тулы, Чат, онбординг) на glass-стиль, используя замороженный API Plan A (`GlassCard`/`GlassSurface`/`auroraBackground`/`glassSource`/`rememberGlassState`).

**Architecture:** Каждый экран: `rememberGlassState()` в корне → `Modifier.auroraBackground()` на корневом контейнере → `.glassSource(state)` на скролл-контенте → `GlassSurface(state, …)` на неподвижной обвязке (бары/нав), `GlassCard(…)` на карточках в скролле. Настоящий blur — только на обвязке; карточки в LazyColumn — light glass без RenderEffect (защита от лагов чата). Существующий идиом `.clip(shape).background(CardDark/surfaceVariant)` заменяется на glass-примитивы.

**Tech Stack:** Kotlin 2.3.0, Compose BOM 2026.05.00, Haze 1.7.2 (через glass-примитивы), Roborazzi 1.59.0 + Robolectric (скриншот-тесты). Ветка `feature/glassmorphism` (поверх `3175bd0` — Plan A + код-ревью-фиксы, смержено с `refactor/agent-loop-cleanup`; их agent-loop-рефакторинг НЕ трогает UI-экраны).

## Global Constraints

- **Ветка исполнения `feature/glassmorphism` @ 3175bd0** (Plan A + код-ревью-фиксы + merge с `refactor/agent-loop-cleanup`; UI-экраны мержем НЕ тронуты — карта/line-рефы валидны). Параллельная сессия смержилась и оставила главный checkout НА `feature/glassmorphism` (чисто) → исполнять можно прямо на checkout, worktree НЕ нужен. Если checkout снова перехватят — уйти в изолированный worktree на feature/glassmorphism. `git add` только точные пути (без чужого WIP).
- **Путь≠package:** файлы на диске `com/example/…`, package `com.aiopenpocket.…`. Импорты glass: `com.aiopenpocket.ui.components.glass.{GlassCard, GlassSurface, GlassState, rememberGlassState, glassSource, auroraBackground}`, `com.aiopenpocket.ui.theme.GlassTokens`.
- **Замороженный glass-API (не менять):** `GlassCard(modifier, shape=GlassTokens.CardShape, onClick:(()->Unit)?=null, tint=GlassTokens.LightTint, content: ColumnScope.()->Unit)` → даёт **Column**; `GlassSurface(state, modifier, shape=RectangleShape, tint=GlassTokens.StrongTint, content: BoxScope.()->Unit)` → даёт **Box**; `Modifier.auroraBackground(baseColor=DarkBg)`; `Modifier.glassSource(state)`; `rememberGlassState(): GlassState`.
- **Перф-гардрейлы:** `GlassSurface` (blur) — ТОЛЬКО на неподвижной обвязке, НИКОГДА внутри item'ов LazyColumn. Аурора статична. Один `rememberGlassState()` на экран, один `glassSource` на его скролл-слой.
- **Row-based карточки:** `GlassCard` даёт `Column` — существующий внутренний `Row` вкладывается как единственный child.
- **Тема всегда тёмная**, minSdk 24 (на <31 glass = tint без blur).
- **Скриншот-тесты:** паттерн репо (`ChatScreenScreenshotTest`/`GreetingScreenshotTest`): `@RunWith(RobolectricTestRunner)`, `@GraphicsMode(NATIVE)`, `@Config(qualifiers=Pixel8, sdk=[36])`, `captureRoboImage`. Использовать preview-скаффолды где есть.
- **Traxer:** рестайл модифицирует файлы СУЩЕСТВУЮЩИХ фич (не новая фича) → после правок `traxer check`; stale-варнинги затронутых фич blessить только после подтверждения, что rationale не изменился (Task 6).

## File Structure

- Modify: `ui/components/AcademyBottomNav.kt` (+ callers `ui/home/HomeScreen.kt`, `ui/roadmap/RoadmapScreen.kt`) — Task 1
- Modify: `ui/home/HomeScreen.kt` — Task 2
- Modify: `ui/agents/AgentsScreen.kt` — Task 3
- Modify: `ui/chat/ChatScreen.kt` — Task 4
- Modify: `ui/onboarding/OnboardingScreen.kt` + `components/{LanguageSelectionStep,AboutMeStep,PersonaSelectionStep,PersonaCard}.kt` — Task 5
- Modify: `traceability.yaml`/re-bless — Task 6
- Test (create): `app/src/test/java/com/aiopenpocket/ui/**/<Zone>GlassScreenshotTest.kt` per zone

> Each task ends with `./gradlew :app:compileDebugKotlin` green + a Roborazzi baseline for that zone + commit (exact paths only). Reuse `ChatPreviewScaffold` (ChatScreen.kt l.1507+) and build minimal preview harnesses for other zones (call the private composables with fake data; if a composable is `private`, render the public screen with a fake VM/state or promote a preview entry as the existing tests do).

---

### Task 1: AcademyBottomNav → GlassSurface (общий, «вау»-элемент, первым)

**Files:**
- Modify: `app/src/main/java/com/example/ui/components/AcademyBottomNav.kt:44-82`
- Test: `app/src/test/java/com/aiopenpocket/ui/components/AcademyBottomNavGlassTest.kt`

**Interfaces:**
- Consumes: `GlassSurface`, `rememberGlassState`, `GlassState`, `GlassTokens`.
- Produces: `AcademyBottomNav(selectedTab, onTabSelected, modifier, glassState: GlassState = rememberGlassState())` — новый необязательный параметр; существующие вызовы не ломаются (дефолт = свежий несвязанный state → tint без blur; экраны подключат реальный blur, передав свой state).

- [ ] **Step 1: Добавить параметр glassState**

В сигнатуру (l.44-49) добавить последним параметром:
```kotlin
  modifier: Modifier = Modifier,
  glassState: GlassState = rememberGlassState(),
```
Импорты: `com.aiopenpocket.ui.components.glass.GlassState`, `com.aiopenpocket.ui.components.glass.rememberGlassState`, `com.aiopenpocket.ui.components.glass.GlassSurface`, `com.aiopenpocket.ui.theme.GlassTokens`.

- [ ] **Step 2: Плавающую таблетку — на GlassSurface**

Текущий внешний `Row` (l.50-82): цепочка `.fillMaxWidth().padding(horizontal=24).padding(top=8,bottom=20).shadow(16.dp, RoundedCornerShape(28.dp), clip=false).clip(RoundedCornerShape(28.dp)).background(CardDark).border(1.dp, BorderDark.copy(alpha=0.6f), RoundedCornerShape(28.dp)).padding(8.dp)` + 3 `AcademyNavItem`.

Заменить `.clip(...).background(CardDark).border(...)` на обёртку `GlassSurface`. Итоговая структура:
```kotlin
val pillShape = RoundedCornerShape(28.dp)
GlassSurface(
  state = glassState,
  shape = pillShape,
  modifier = modifier
    .fillMaxWidth()
    .padding(horizontal = 24.dp)
    .padding(top = 8.dp, bottom = 20.dp)
    .shadow(16.dp, pillShape, clip = false),
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // существующие 3 AcademyNavItem без изменений
  }
}
```
(`GlassSurface` даёт `Box`; кладём `Row` внутрь. `shadow` — до GlassSurface, blik-бордер даёт сам GlassSurface. Убрать прежние `.clip/.background(CardDark)/.border`.)

- [ ] **Step 3: Скриншот-baseline**

Создать `AcademyBottomNavGlassTest.kt` (паттерн репо, sdk=[36]): в `Box(Modifier.fillMaxSize().auroraBackground())` отрисовать `AcademyBottomNav(selectedTab=AcademyTab.Chat, onTabSelected={})` внизу; `captureRoboImage("src/test/screenshots/bottomnav_glass.png")`. `recordRoborazziDebug` → проверить визуал (стеклянная таблетка над ауророй, бордер-блик, тень) → `verifyRoborazziDebug`.

- [ ] **Step 4: Compile + commit**

`./gradlew :app:compileDebugKotlin` → SUCCESSFUL. Коммит `feat(ui): glass floating bottom nav` (git add AcademyBottomNav.kt + тест + png).

---

### Task 2: Home → аурора + GlassCard + glass-нав

**Files:**
- Modify: `app/src/main/java/com/example/ui/home/HomeScreen.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/home/HomeGlassScreenshotTest.kt`

**Interfaces:**
- Consumes: `auroraBackground`, `rememberGlassState`, `glassSource`, `GlassCard`, `AcademyBottomNav(glassState=…)`.

- [ ] **Step 1: Корень — аурора + state**

В `HomeScreen` (l.66) в начале тела: `val glassState = rememberGlassState()`. Обернуть `Scaffold` (l.102) в `Box(Modifier.fillMaxSize().auroraBackground())`, у `Scaffold` заменить `containerColor = PersonaQuoteBg` → `containerColor = Color.Transparent`. У `topBar = TopAppBar` (l.105) заменить `containerColor = PersonaQuoteBg` → `Color.Transparent` (аурора видна за топбаром). Импорты glass + `androidx.compose.ui.graphics.Color`.

- [ ] **Step 2: glassSource на ленту + glassState в нав**

`LazyColumn` (l.160): добавить в его `Modifier` `.glassSource(glassState)`. `bottomBar = AcademyBottomNav(...)` (l.147): добавить аргумент `glassState = glassState`.

- [ ] **Step 3: Карточки → GlassCard**

Для каждой конвертировать внешнюю поверхность `.clip(CardShape).background(CardDark)…` в `GlassCard`:
- `NewChatCard` (l.267-314): текущий корневой `Row .clip(CardShape).background(CardDark).clickable{onClick}.padding(16)` → `GlassCard(modifier=Modifier.fillMaxWidth(), onClick=onClick) { Row(Modifier.padding(16.dp), …) { <существующее содержимое> } }`.
- `AgentsEntryCard` (l.316-363): аналогично.
- `RecentChatCard` (l.393-442): аналогично.
- `CourseCard` (l.444-517): корневой `Column .clip(CardShape).background(CardDark).clickable.padding(...)` → `GlassCard(onClick=onClick) { <существующий Column-контент, включая LinearProgressIndicator> }` (GlassCard уже даёт Column — вложить контент напрямую, убрав дублирующий внешний Column).
- `EmptyHint` (l.519-533): `Box .clip(CardShape).background(CardDark)` → `GlassCard { <текст> }` (без onClick).
- `HomeHero` (l.230), `SectionHeader` (l.365) — НЕ трогать (декор/без карточки).

(Убрать теперь-неиспользуемый локальный `CardShape` (l.63), если он больше нигде не нужен, — GlassCard.shape по дефолту `GlassTokens.CardShape` = тот же 20.dp.)

- [ ] **Step 4: Скриншот + compile + commit**

`HomeGlassScreenshotTest.kt`: отрисовать `HomeScreen(...)` с no-op колбэками (или минимальный preview-скаффолд, если экран требует VM — следовать паттерну существующих тестов) → baseline `home_glass.png`. `recordRoborazziDebug`/`verifyRoborazziDebug`. `compileDebugKotlin` green. Коммит `feat(ui): glass restyle Home (aurora + glass cards + glass nav)`.

---

### Task 3: Тулы (AgentsScreen) → аурора + GlassCard

**Files:**
- Modify: `app/src/main/java/com/example/ui/agents/AgentsScreen.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/agents/AgentsGlassScreenshotTest.kt`

**Interfaces:** Consumes `auroraBackground`, `rememberGlassState`, `glassSource`, `GlassCard`.

- [ ] **Step 1: Корень — аурора + прозрачный коллапс-бар**

В `AgentsScreen` (l.49): `val glassState = rememberGlassState()`. Обернуть `AcademyCollapsingScaffold` (l.56) в `Box(Modifier.fillMaxSize().auroraBackground())` и передать ему `containerColor = Color.Transparent` (аурора видна за коллапс-топбаром; сам `AcademyCollapsingScaffold` — общий компонент, его внутреннюю структуру НЕ меняем, только прокидываем прозрачный containerColor через существующий параметр). Настоящий blur на коллапс-баре — вне скоупа (не трогаем shared scaffold).

- [ ] **Step 2: glassSource + AgentCard → GlassCard**

`LazyColumn` (l.67) в content-лямбде: добавить `.glassSource(glassState)`. `AgentCard` (l.93-155): корневой `Column .clip(RoundedCornerShape(16.dp)).background(CardDark).clickable{onClick}.padding(16)` → `GlassCard(onClick=onClick, shape=RoundedCornerShape(16.dp)) { <существующий контент: иконка Row + FlowRow чипсов> }`. `CapabilityChip` (l.157) — оставить (акцентная плашка).

- [ ] **Step 3: Скриншот + compile + commit**

`AgentsGlassScreenshotTest.kt` → baseline `agents_glass.png`. compile green. Коммит `feat(ui): glass restyle Tools (aurora + glass agent cards)`.

---

### Task 4: Чат → аурора + glass-обвязка (консервативно)

**Files:**
- Modify: `app/src/main/java/com/example/ui/chat/ChatScreen.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/chat/ChatGlassScreenshotTest.kt`

**Interfaces:** Consumes `auroraBackground`, `rememberGlassState`, `glassSource`, `GlassSurface`.

> ⚠️ Перф-критичный экран (чинили лаги стрима). Blur — ТОЛЬКО на неподвижной обвязке. Пузыри (`MessageBubble`, `StreamingBubble`) и driven-ui карточки в этой итерации НЕ трогаем (сохранить цвет user/assistant и не класть RenderEffect в LazyColumn).

- [ ] **Step 1: Аурора + state + glassSource**

В `ChatScreen` (l.125): `val glassState = rememberGlassState()`. На внешний `Box(Modifier.fillMaxSize())` (l.313) добавить `.auroraBackground()`. `Scaffold` (l.314) — без явного containerColor уже прозрачен к фону; убедиться, что фон Scaffold не перекрывает аурору (при необходимости `containerColor = Color.Transparent`). На `LazyColumn` сообщений (l.445) добавить `.glassSource(glassState)`.

- [ ] **Step 2: Топбар и инпут-бар → GlassSurface**

- Верхний `Column` в `topBar` (l.315-431): обернуть его содержимое (TopAppBar/ChatSearchBar + fade) в `GlassSurface(state=glassState)` (прямоугольная форма — дефолт). `barColor = surface.copy(alpha=0.85f)` (l.318) заменить на `GlassTokens.StrongTint`-тон через GlassSurface (убрать ручную полупрозрачность; tint даёт GlassSurface).
- `ChatInput` (l.1304-1434): корневой `Column .clip(RoundedCornerShape(24.dp)).border(1.dp, primary.copy(alpha=0.7f), RoundedCornerShape(24.dp))` (l.1341-1352) → обернуть в `GlassSurface(state=glassState, shape=RoundedCornerShape(24.dp))` (внутренний контент `Column` вкладывается как child Box'а). Прежний ручной border убрать (GlassSurface даёт blik-бордер); при желании сохранить фиолетовый акцент-бордер поверх — оставить как второй `.border` внутри.

- [ ] **Step 3: Синхронизировать preview-скаффолд**

`ChatPreviewScaffold`/`PreviewComposerBar` (l.1507-1738) отражают реальные бары — применить те же glass-обёртки, чтобы скриншот-тест был правдив.

- [ ] **Step 4: Скриншот + перф-чек + compile + commit**

`ChatGlassScreenshotTest.kt` через `ChatPreviewScaffold` → baseline `chat_glass.png`. Прогнать существующий `ChatScreenScreenshotTest` — не должен сломаться (или обновить baseline осознанно). compile green. Коммит `feat(ui): glass chat chrome (aurora + glass topbar/input, bubbles untouched)`.

---

### Task 5: Онбординг → аурора + glass-обвязка/карточки/инпуты

**Files:**
- Modify: `app/src/main/java/com/example/ui/onboarding/OnboardingScreen.kt`, `.../components/{LanguageSelectionStep,AboutMeStep,PersonaSelectionStep,PersonaCard}.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/onboarding/OnboardingGlassScreenshotTest.kt`

**Interfaces:** Consumes `auroraBackground`, `rememberGlassState`, `glassSource`, `GlassCard`, `GlassSurface`, `GlassTokens`.

- [ ] **Step 1: Обвязка OnboardingContent**

`OnboardingContent` (l.82): `val glassState = rememberGlassState()`. Обернуть `Scaffold` (l.104) в `Box(Modifier.fillMaxSize().auroraBackground())`; `Scaffold.containerColor = Color.Transparent`; `TopAppBar` (l.104-116) `containerColor` → `Color.Transparent`. `OnboardingBottomBar` (l.138-181): его корневой контейнер обернуть в `GlassSurface(state=glassState)`. Контентную `Column` (l.122) с прокруткой шага — `.glassSource(glassState)` (если она скроллится; иначе glassSource на внутренний скролл шага).

- [ ] **Step 2: PersonaSelectionStep (свой Scaffold)**

`PersonaSelectionStep` (l.42-153): `val glassState = rememberGlassState()`; обернуть его `Scaffold` (l.51) в `Box.auroraBackground()`, `containerColor=Color.Transparent`, `TopAppBar`/`bottomBar Row` → `GlassSurface`; `LazyColumn` персон (l.142) `.glassSource(glassState)`.

- [ ] **Step 3: Карточки/поля**

- `LanguageSelectionStep` строки языков (l.75-86): `Row .clip(RoundedCornerShape(14.dp)).border(...).background(if selected primaryContainer.copy(0.4f) else surface).clickable` → `GlassCard(onClick=…, shape=RoundedCornerShape(14.dp), tint = if(selected) PurpleAccent.copy(alpha=0.18f) else GlassTokens.LightTint) { <существующий Row: флаг + тексты + Check> }`; выбранной оставить 2dp `primary`-бордер поверх.
- `PersonaCard` (l.44-110): внешний `Box .clip(RoundedCornerShape(12.dp)).background(PersonaCardBg).clickable` → `GlassCard(onClick=onSelect, shape=RoundedCornerShape(12.dp)) { <существующий Column> }`; вложенные `PersonaQuoteBg`-инсеты (StatRow/PerksRow/QuoteRow) оставить тёмными recess'ами; selected-бордер/бейдж сохранить.
- `AboutMeStep` `OutlinedTextField` (l.70-83): заменить его `colors`/контур на стеклянный вид — обернуть поле в `Box .clip(GlassTokens.CardShape).background(GlassTokens.LightTint).border(1.dp, GlassTokens.BorderBrush, GlassTokens.CardShape)` и у `OutlinedTextField` сделать контейнеры прозрачными (`OutlinedTextFieldDefaults.colors(unfocused/focusedContainerColor=Color.Transparent, unfocused/focusedBorderColor=Color.Transparent)`), сохранив `testTag("about_me_input")` и лимиты.
- `IntroStreamingStep`/`WrapupStep` пузыри и общий `StreamingBubble` — в этой итерации не трогаем (shared с чатом; консервативно).

- [ ] **Step 4: Скриншот + compile + commit**

`OnboardingGlassScreenshotTest.kt` (по шагам LANGUAGE/PROFILE/PERSONA, минимальные preview-обёртки) → baseline `onboarding_glass.png`. compile green. Коммит `feat(ui): glass restyle onboarding (aurora + glass chrome/cards/input)`.

---

### Task 6: Traxer — re-bless затронутых фич

**Files:** Modify `traceability.yaml`/`traceability.lock`.

**Interfaces:** Produces `traxer check` → 0 errors, 0 warnings.

- [ ] **Step 1: Проверить дрейф**

`traxer check`. Рестайл модифицировал файлы фич `ai-academy-onboarding-5d51bb`, `streaming-llm-chat-6595a1`, `multi-agent-orchestration-27b8e3` и др. (по тому, чьи файлы тронуты) → они станут stale.

- [ ] **Step 2: Bless после подтверждения rationale**

Для каждой stale-фичи подтвердить, что её `rationale` в `traceability.yaml` всё ещё верен (рестайл — только визуал, поведение не менялось), затем `traxer bless <id>`. НЕ трогать фичи, чьи файлы рестайл не касался.

- [ ] **Step 3: Commit**

`traxer check` → 0/0. Коммит `chore(traxer): re-bless features touched by glass restyle` (git add traceability.lock/.yaml).

---

## Self-Review

**1. Spec coverage (спека, раздел 2):** Home ✓ (T2), Чат ✓ (T4, blur только обвязка), Тулы ✓ (T3), Нижняя навигация ✓ (T1), Онбординг ✓ (T5). Перф-гардрейлы ✓ (Global Constraints + T4). Traxer ✓ (T6).

**2. Placeholder scan:** нет TBD/TODO; каждая конверсия карточки/бара имеет якорь (файл:строки) + целевой glass-примитив.

**3. Type consistency:** `GlassCard`(Column-контент), `GlassSurface`(Box-контент, требует `state`), `glassSource(state)`, `auroraBackground()`, `AcademyBottomNav(…, glassState)` — согласованы между задачами; `glassState` создаётся `rememberGlassState()` один раз на экран и прокидывается в нав/обвязку.

**Открытые риски (для реализации):** (a) приватные экран-composable могут требовать VM/fake-state для скриншота — следовать паттерну существующих тестов (ChatPreviewScaffold); (b) `AcademyCollapsingScaffold`/`StreamingBubble` — shared, в этой итерации трогаем минимально (прозрачный containerColor / не трогаем); (c) прозрачный containerColor у Scaffold не должен ломать инсеты — проверять визуально по baseline.

## Execution Handoff

Порядок: T1 (нав, общий) → T2 (Home, потребляет нав) → T3 (Тулы) → T4 (Чат) → T5 (Онбординг) → T6 (traxer). Каждая — свежий implementer + task-ревью; в конце whole-branch ревью. Исполнять в изолированном worktree на `feature/glassmorphism`.
