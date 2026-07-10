# Glass-фундамент (Plan A) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Собрать переиспользуемый glass-фундамент (Haze + токены + аурора-фон + `GlassSurface`/`GlassCard`), на который затем опираются Plan B (рестайл 5 зон) и Plan C (инспектор агента).

**Architecture:** Вся зависимость от Haze изолирована в одном файле `GlassSurface.kt` — вызывающие получают Haze-free API (`rememberGlassState`, `Modifier.glassSource`, `GlassSurface`, `GlassCard`, `Modifier.auroraBackground`). Аурора рисуется в `drawBehind` (ноль рекомпозиций) и служит `hazeSource` под стеклом. «Сильное» стекло (bars) даёт настоящий backdrop-blur на API 31+; «слабое» (карточки в скролле) — tint+бордер без blur, безопасно внутри LazyColumn.

**Tech Stack:** Kotlin 2.3.0, Jetpack Compose (BOM 2026.05.00), Material 3, Haze 1.7.2, Roborazzi 1.59.0 + Robolectric 4.16.1 (скриншот-тесты), version-catalog Gradle.

## Global Constraints

- **minSdk 24**, compileSdk/targetSdk 36, JVM 21. На API < 31 backdrop-blur недоступен → Haze отдаёт tint-фолбэк; это приемлемый вид (стиль держат tint+бордер).
- **Путь vs package расходятся намеренно:** исходники main лежат под `app/src/main/java/com/example/…`, но `package` объявляется как `com.aiopenpocket.…`. Новые файлы следуют этому: диск `com/example/ui/...`, package `com.aiopenpocket.ui.*`.
- **Тесты** — под `app/src/test/java/com/aiopenpocket/…`, package `com.aiopenpocket.…` (новая тест-конвенция репо).
- **Вся Haze-поверхность живёт только в `GlassSurface.kt`.** Если API запиненной версии отличается — правки только там (ловится build-спайком Task 1).
- **Roborazzi-скриншоты — только sdk ≥ 35** (`@Config(sdk = [36])`), иначе неверные края blur (CLAMP tile mode).
- **Тема всегда тёмная** (`MyApplicationTheme`, darkTheme=true) — glass-токены статичны, без CompositionLocal.
- **Haze 1.7.2 API:** `rememberHazeState()`, `Modifier.hazeSource(state)`, `Modifier.hazeEffect(state, style = HazeStyle(...))`. (2.0 — alpha, не берём.)
- **Traxer (CLAUDE.md):** фича регистрируется в `traceability.yaml`, маркеры `@trace`, коммиты с трейлером `Trace:` — см. Task 6.

## File Structure

- Modify: `gradle/libs.versions.toml` — версия + 2 библиотеки Haze
- Modify: `app/build.gradle.kts:88-157` (блок dependencies) — 2 `implementation`
- Create: `app/src/main/java/com/example/ui/theme/GlassTokens.kt` — токены (цвета/dim/бордер/аурора-спек)
- Create: `app/src/main/java/com/example/ui/components/glass/AuroraBackground.kt` — `Modifier.auroraBackground()`
- Create: `app/src/main/java/com/example/ui/components/glass/GlassSurface.kt` — `GlassState`, `rememberGlassState`, `glassSource`, `GlassSurface` (единственный Haze-файл)
- Create: `app/src/main/java/com/example/ui/components/glass/GlassCard.kt` — light glass, без Haze
- Test: `app/src/test/java/com/aiopenpocket/ui/components/glass/GlassTokensTest.kt` — unit на значения токенов
- Test: `app/src/test/java/com/aiopenpocket/ui/components/glass/GlassScreenshotTest.kt` — Roborazzi baseline трёх компонентов
- Modify: `traceability.yaml` — запись новой фичи

---

### Task 1: Подключить Haze 1.7.2

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: —
- Produces: артефакты `libs.haze`, `libs.haze.materials` доступны для `implementation`.

- [ ] **Step 1: Добавить версию и библиотеки в каталог**

В `gradle/libs.versions.toml`, в секцию `[versions]` (после строки `metro = "1.1.1"`, ~стр. 43) добавить:

```toml
haze = "1.7.2"
```

В секцию `[libraries]` (после строки `metrox-viewmodel-compose = ...`, ~стр. 103) добавить:

```toml
haze = { group = "dev.chrisbanes.haze", name = "haze", version.ref = "haze" }
haze-materials = { group = "dev.chrisbanes.haze", name = "haze-materials", version.ref = "haze" }
```

- [ ] **Step 2: Добавить зависимости в модуль app**

В `app/build.gradle.kts`, в блок `dependencies { … }` (после строки `implementation(libs.coil.svg)`, стр. 114) добавить:

```kotlin
  implementation(libs.haze)
  implementation(libs.haze.materials)
```

- [ ] **Step 3: Проверить, что зависимость резолвится и проект собирается**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep chrisbanes`
Expected: строки `dev.chrisbanes.haze:haze:1.7.2` и `dev.chrisbanes.haze:haze-materials:1.7.2`.

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Haze ещё не используется — проверяем только резолюцию/совместимость с Compose BOM 2026.05.00).

> Если компиляция падает на несовместимости Compose ↔ Haze: поднять `haze` до последнего 2.0.0-alpha (`2.0.0-alpha03`) и учесть, что в 2.0 модификаторы называются так же (`hazeSource`/`hazeEffect`), но свойства blur заворачиваются в `blurEffect { … }`, а артефакт blur вынесен в `dev.chrisbanes.haze:haze-blur`. Правки затронут только `GlassSurface.kt`.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Haze 1.7.2 (glassmorphism backdrop-blur)"
```

---

### Task 2: GlassTokens — токены стекла

**Files:**
- Create: `app/src/main/java/com/example/ui/theme/GlassTokens.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/components/glass/GlassTokensTest.kt`

**Interfaces:**
- Consumes: `DarkBg`, `PurpleAccent`, `PurpleAccentDark` из `com.aiopenpocket.ui.theme` (`Color.kt`).
- Produces:
  - `object GlassTokens` с `StrongTint: Color`, `LightTint: Color`, `BlurRadius: Dp`, `fun borderBrush(): Brush`, `data class AuroraSpot(color: Color, center: Offset, radiusFraction: Float)`, `AuroraSpots: List<AuroraSpot>`.

- [ ] **Step 1: Написать падающий тест**

Создать `app/src/test/java/com/aiopenpocket/ui/components/glass/GlassTokensTest.kt`:

```kotlin
package com.aiopenpocket.ui.components.glass

import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTokensTest {

  @Test fun strongTintIsMoreOpaqueThanLight() {
    assertTrue(GlassTokens.StrongTint.alpha > GlassTokens.LightTint.alpha)
  }

  @Test fun blurRadiusIs20dp() {
    assertEquals(20.dp, GlassTokens.BlurRadius)
  }

  @Test fun hasThreeAuroraSpots() {
    assertEquals(3, GlassTokens.AuroraSpots.size)
  }

  @Test fun auroraCentersAreWithinUnitSquare() {
    GlassTokens.AuroraSpots.forEach { spot ->
      assertTrue(spot.center.x in 0f..1f)
      assertTrue(spot.center.y in 0f..1f)
    }
  }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что падает**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aiopenpocket.ui.components.glass.GlassTokensTest"`
Expected: FAIL с «unresolved reference: GlassTokens».

- [ ] **Step 3: Реализовать GlassTokens**

Создать `app/src/main/java/com/example/ui/theme/GlassTokens.kt`:

```kotlin
package com.aiopenpocket.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Токены глассморфизма. Тема всегда тёмная ([MyApplicationTheme]) — значения
 * статичны, CompositionLocal не нужен.
 */
object GlassTokens {

  /** Тонировка «сильного» стекла (bars/навигация) поверх тёмной подложки. */
  val StrongTint: Color = Color.White.copy(alpha = 0.10f)

  /** Тонировка «слабого» стекла (карточки в скролле, без blur). */
  val LightTint: Color = Color.White.copy(alpha = 0.06f)

  /** Радиус backdrop-blur сильного стекла. */
  val BlurRadius: Dp = 20.dp

  /** Блик на грани: светлее сверху, гаснет вниз. */
  fun borderBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
      Color.White.copy(alpha = 0.25f),
      Color.White.copy(alpha = 0.05f),
    ),
  )

  /** Пятно ауроры; center/radiusFraction — доли размера холста (0..1). */
  data class AuroraSpot(val color: Color, val center: Offset, val radiusFraction: Float)

  /** 3 статичных пятна: фиолет сверху-слева, тёмный фиолет справа, глубокий синий снизу. */
  val AuroraSpots: List<AuroraSpot> = listOf(
    AuroraSpot(PurpleAccent.copy(alpha = 0.22f), Offset(0.15f, 0.10f), 0.60f),
    AuroraSpot(PurpleAccentDark.copy(alpha = 0.45f), Offset(0.90f, 0.25f), 0.70f),
    AuroraSpot(Color(0xFF1B2A4A).copy(alpha = 0.55f), Offset(0.50f, 0.95f), 0.90f),
  )
}
```

- [ ] **Step 4: Запустить тест — убедиться, что проходит**

Run: `./gradlew :app:testDebugUnitTest --tests "com.aiopenpocket.ui.components.glass.GlassTokensTest"`
Expected: PASS (4 теста).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/ui/theme/GlassTokens.kt app/src/test/java/com/aiopenpocket/ui/components/glass/GlassTokensTest.kt
git commit -m "feat: GlassTokens (glass tint/blur/border/aurora tokens)"
```

---

### Task 3: AuroraBackground — фон

**Files:**
- Create: `app/src/main/java/com/example/ui/components/glass/AuroraBackground.kt`

**Interfaces:**
- Consumes: `GlassTokens.AuroraSpots`, `DarkBg`.
- Produces: `fun Modifier.auroraBackground(baseColor: Color = DarkBg): Modifier`.

- [ ] **Step 1: Реализовать модификатор**

Создать `app/src/main/java/com/example/ui/components/glass/AuroraBackground.kt`:

```kotlin
package com.aiopenpocket.ui.components.glass

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.aiopenpocket.ui.theme.DarkBg
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * Тёмная база + статичные радиальные пятна ауроры. Рисуется в [drawBehind] —
 * ноль рекомпозиций. Наносить на корневой контейнер экрана; поверх кладётся
 * контент (обычно он же — hazeSource через [glassSource]).
 */
fun Modifier.auroraBackground(baseColor: Color = DarkBg): Modifier =
  this.drawBehind {
    drawRect(baseColor)
    GlassTokens.AuroraSpots.forEach { spot ->
      val center = Offset(size.width * spot.center.x, size.height * spot.center.y)
      val radius = maxOf(size.width, size.height) * spot.radiusFraction
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(spot.color, Color.Transparent),
          center = center,
          radius = radius,
        ),
        radius = radius,
        center = center,
      )
    }
  }
```

- [ ] **Step 2: Проверить компиляцию**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Визуальная проверка — скриншот-тестом в Task 5.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/components/glass/AuroraBackground.kt
git commit -m "feat: auroraBackground modifier (static aurora field)"
```

---

### Task 4: GlassCard — «слабое» стекло

**Files:**
- Create: `app/src/main/java/com/example/ui/components/glass/GlassCard.kt`

**Interfaces:**
- Consumes: `GlassTokens.LightTint`, `GlassTokens.borderBrush()`.
- Produces: `@Composable fun GlassCard(modifier: Modifier, shape: Shape, onClick: (() -> Unit)?, tint: Color, content: @Composable ColumnScope.() -> Unit)`.

- [ ] **Step 1: Реализовать компонент**

Создать `app/src/main/java/com/example/ui/components/glass/GlassCard.kt`:

```kotlin
package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * «Слабое» стекло — имитация без blur (tint + блик-бордер). Безопасно внутри
 * item'ов LazyColumn (никакого RenderEffect на прокрутке).
 */
@Composable
fun GlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = RoundedCornerShape(20.dp),
  onClick: (() -> Unit)? = null,
  tint: Color = GlassTokens.LightTint,
  content: @Composable ColumnScope.() -> Unit,
) {
  val base = modifier
    .clip(shape)
    .background(tint)
    .border(1.dp, GlassTokens.borderBrush(), shape)
  Column(
    modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
    content = content,
  )
}
```

- [ ] **Step 2: Проверить компиляцию**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/components/glass/GlassCard.kt
git commit -m "feat: GlassCard (light glass, blur-free, LazyColumn-safe)"
```

---

### Task 5: GlassSurface + связка состояния + скриншот-baseline

**Files:**
- Create: `app/src/main/java/com/example/ui/components/glass/GlassSurface.kt`
- Test: `app/src/test/java/com/aiopenpocket/ui/components/glass/GlassScreenshotTest.kt`

**Interfaces:**
- Consumes: Haze 1.7.2 (`HazeState`, `rememberHazeState`, `hazeSource`, `hazeEffect`, `HazeStyle`, `HazeTint`), `DarkBg`, `GlassTokens`.
- Produces (Haze-free API для Plan B/C):
  - `typealias GlassState = HazeState`
  - `@Composable fun rememberGlassState(): GlassState`
  - `fun Modifier.glassSource(state: GlassState): Modifier`
  - `@Composable fun GlassSurface(state: GlassState, modifier: Modifier, shape: Shape, tint: Color, content: @Composable BoxScope.() -> Unit)`

- [ ] **Step 1: Реализовать GlassSurface (единственный Haze-файл)**

Создать `app/src/main/java/com/example/ui/components/glass/GlassSurface.kt`:

```kotlin
package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.DarkBg
import com.aiopenpocket.ui.theme.GlassTokens
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * ЕДИНСТВЕННЫЙ файл, импортирующий Haze. Если API запиненной версии отличается —
 * правки только здесь (Task 1 build-спайк ловит это заранее).
 */

/** Токен связи «источник ↔ стекло». Обёрнут, чтобы вызывающие не импортировали Haze. */
typealias GlassState = HazeState

@Composable
fun rememberGlassState(): GlassState = rememberHazeState()

/** Пометить контент источником, который размывается под стеклом. */
fun Modifier.glassSource(state: GlassState): Modifier = this.hazeSource(state)

/**
 * «Сильное» стекло с настоящим backdrop-blur (API 31+; на < 31 Haze даёт tint без
 * размытия). Для неподвижной обвязки: топбары, инпут-бары, нижняя навигация.
 */
@Composable
fun GlassSurface(
  state: GlassState,
  modifier: Modifier = Modifier,
  shape: Shape = RectangleShape,
  tint: Color = GlassTokens.StrongTint,
  content: @Composable BoxScope.() -> Unit,
) {
  Box(
    modifier = modifier
      .clip(shape)
      .hazeEffect(
        state = state,
        style = HazeStyle(
          backgroundColor = DarkBg,
          tints = listOf(HazeTint(tint)),
          blurRadius = GlassTokens.BlurRadius,
        ),
      )
      .border(1.dp, GlassTokens.borderBrush(), shape),
    content = content,
  )
}
```

> Если сборка укажет на несовпадение сигнатур `HazeStyle`/`hazeEffect` в 1.7.2 (напр. `tint` вместо `tints`, или scope-лямбда `hazeEffect(state) { blurRadius = …; tints = … }` вместо `style =`): править ТОЛЬКО этот файл по KDoc запиненной версии. Публичный API (typealias/rememberGlassState/glassSource/GlassSurface) не меняется — Plan B/C от правок не зависят.

- [ ] **Step 2: Написать скриншот-тест (характеризация трёх компонентов)**

Создать `app/src/test/java/com/aiopenpocket/ui/components/glass/GlassScreenshotTest.kt`:

```kotlin
package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GlassScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test fun glass_components_over_aurora() {
    composeTestRule.setContent {
      MyApplicationTheme {
        val state = rememberGlassState()
        Box(Modifier.fillMaxSize().auroraBackground()) {
          LazyColumn(Modifier.fillMaxSize().glassSource(state)) {
            item {
              GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Light glass card", Modifier.padding(16.dp))
              }
            }
          }
          GlassSurface(
            state = state,
            modifier = Modifier.fillMaxWidth().height(64.dp),
          ) { Text("Strong glass bar", Modifier.padding(16.dp)) }
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/glass_components.png")
  }
}
```

- [ ] **Step 3: Записать baseline-скриншот**

Run: `./gradlew :app:recordRoborazziDebug --tests "com.aiopenpocket.ui.components.glass.GlassScreenshotTest"`
Expected: BUILD SUCCESSFUL; появился файл `app/src/test/screenshots/glass_components.png` (аурора + светлая карточка + стеклянный бар).

- [ ] **Step 4: Глазами проверить baseline**

Открыть `app/src/test/screenshots/glass_components.png`: виден тёмный аурора-фон с фиолетово-синими пятнами, полупрозрачная карточка с бликом-бордером, и стеклянный бар сверху. Если пусто/чёрно — проверить, что sdk=[36] и `auroraBackground` применён до `glassSource`.

- [ ] **Step 5: Проверить, что verify проходит на записанном baseline**

Run: `./gradlew :app:verifyRoborazziDebug --tests "com.aiopenpocket.ui.components.glass.GlassScreenshotTest"`
Expected: PASS (сравнение с только что записанным baseline).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/ui/components/glass/GlassSurface.kt app/src/test/java/com/aiopenpocket/ui/components/glass/GlassScreenshotTest.kt app/src/test/screenshots/glass_components.png
git commit -m "feat: GlassSurface (strong backdrop-blur) + glass state API + screenshot baseline"
```

---

### Task 6: Traxer-регистрация фичи

**Files:**
- Modify: `traceability.yaml`
- Modify: `GlassSurface.kt`, `GlassTokens.kt` (маркеры `@trace`)

**Interfaces:**
- Consumes: —
- Produces: фича `glass-design-system-<hex>` в `traceability.yaml`, связанная с кодом через `@trace`.

- [ ] **Step 1: Сгенерировать frozen id**

Run: `traxer id`
Expected: строка вида `glass-design-system-ab12cd` (запомнить как `<TRACE_ID>`).

> Если бинарь недоступен под этим именем — использовать скилл `trace` (он знает точный вызов traxer в этом репо).

- [ ] **Step 2: Добавить запись в traceability.yaml**

В `traceability.yaml`, в список `features:` добавить (id — из Step 1):

```yaml
  - id: glass-design-system-<hex>
    title: "Glass-дизайн-система (глассморфизм)"
    rationale: "Переиспользуемый glass-слой (Haze 1.7.2 backdrop-blur + токены + аурора-фон + GlassSurface/GlassCard). Вся Haze-поверхность изолирована в GlassSurface.kt, чтобы смена версии Haze не растекалась по экранам. Сильное стекло (blur) — только на неподвижной обвязке; слабое (tint без blur) — безопасно в LazyColumn, чтобы не вернуть лаги стрима чата."
```

- [ ] **Step 3: Проставить маркеры @trace**

В `GlassSurface.kt` — в KDoc-блок над `typealias GlassState` добавить строку `@trace <TRACE_ID>`.
В `GlassTokens.kt` — в KDoc над `object GlassTokens` добавить строку `@trace <TRACE_ID>`.

- [ ] **Step 4: Проверить traxer**

Run: `traxer check`
Expected: `0 error(s)`; новая фича видна как связанная с кодом (не «фича без кода»).

- [ ] **Step 5: Commit с трейлером**

```bash
git add traceability.yaml app/src/main/java/com/example/ui/components/glass/GlassSurface.kt app/src/main/java/com/example/ui/theme/GlassTokens.kt
git commit -m "chore(traxer): register glass-design-system feature

Trace: glass-design-system-<hex>"
```

---

## Self-Review

**1. Spec coverage (раздел «1. Дизайн-система стекла» спеки):**
- Haze + фолбэк → Task 1 ✓
- `GlassTokens.kt` (tint/бордер/blur/аурора-цвета) → Task 2 ✓
- `AuroraBackground.kt` (drawBehind, hazeSource) → Task 3 (+ `glassSource` в Task 5) ✓
- `GlassSurface.kt` (strong glass, hazeEffect) → Task 5 ✓
- `GlassCard.kt` (light glass, без blur) → Task 4 ✓
- Traxer-регистрация (раздел «Traxer» спеки) → Task 6 ✓
- Установка аурора-фона + `hazeState` в конкретные экраны — **осознанно в Plan B/C** (фундамент даёт API; монтаж по экранам — при рестайле).

**2. Placeholder scan:** `<hex>`/`<TRACE_ID>` — не плейсхолдеры кода, а значение из `traxer id` (Task 6 Step 1), заполняется в рамках задачи. Прочих TBD/TODO нет.

**3. Type consistency:** `GlassState`, `rememberGlassState`, `Modifier.glassSource`, `Modifier.auroraBackground`, `GlassSurface(state,…)`, `GlassCard(…)`, `GlassTokens.{StrongTint,LightTint,BlurRadius,borderBrush,AuroraSpots}` — имена совпадают между определением (Tasks 2–5) и потреблением (тест Task 5, будущие Plan B/C).

## Что дальше (после Plan A)

- **Plan B — Рестайл 5 зон** (Home, Чат, Тулы, нижняя навигация, онбординг) — пишется против зафиксированного здесь API.
- **Plan C — Экран-инспектор агента** — тоже поверх этого API.
