# Десктопная читалка уроков — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Отдельное десктопное окно, которое читает `app/src/main/assets/lessons/*.json` прямо с диска и показывает урок целиком одной прокруткой, без сборки Android-приложения.

**Architecture:** Самостоятельная Gradle-сборка в `tools/lesson-viewer/`, отсутствующая в корневом `settings.gradle.kts`. Она ничего не импортирует из `:app` — ни модулей, ни исходников — и имеет собственные модели данных, разбираемые `kotlinx.serialization` с `ignoreUnknownKeys`. Логика, у которой есть правильный и неправильный ответ (индексы правильных вариантов, порядок сортировки), вынесена в чистые функции и покрыта тестами; сам UI не тестируется и проверяется глазами.

**Tech Stack:** Kotlin/JVM, Compose Multiplatform (desktop), kotlinx-serialization-json, JUnit 4.

Спека: [docs/superpowers/specs/2026-07-25-lesson-viewer-design.md](../specs/2026-07-25-lesson-viewer-design.md)

## Global Constraints

- **Ни одной правки вне `tools/lesson-viewer/` и `docs/superpowers/`.** Ни `:app`, ни корневой `settings.gradle.kts`, ни `gradle/libs.versions.toml`, ни `traceability.yaml`.
- **Вне traxer.** Никаких маркеров `@trace`, никаких записей в `traceability.yaml`, никакого трейлера `Trace:` в коммитах. Это решение раздела 2.8 спеки.
- **Каталог `tools/lesson-viewer/` не добавляется в корневой `settings.gradle.kts`** — иначе Android Studio начнёт его индексировать и появятся дубли классов.
- **Никакого кода из `:app`.** Ни `srcDir` на его исходники, ни копирования его файлов. Единственное, что общего, — формат JSON-уроков.
- Kotlin-версия вьюера **намеренно не привязана** к Kotlin 2.3.0 из `:app`: сборки независимы, общего кода нет, поэтому вьюер берёт заведомо рабочую пару Kotlin + Compose Multiplatform.
- JDK: 17 (`jvmToolchain(17)`). На машине стоит JBR 17.0.14, `JAVA_HOME=C:\Users\jqwou\.jdks\jbr-17.0.14`.
- Все файлы читаются **в UTF-8 явно**. Системная кодировка на этой машине не UTF-8 (проверено: вывод русского JSON в консоль приезжает кракозябрами). `File.readText()` в Kotlin по умолчанию UTF-8 — не заменять его на `Charset.defaultCharset()`.
- Ветка: `tools/lesson-viewer`. В рабочем дереве лежат несохранённые правки пользователя в `app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailScreen.kt` и `AgentDetailSections.kt` — **не коммитить их и не трогать**. Каждый `git add` — только явными путями, никогда `git add -A` и никогда `git commit -a`.

---

## Структура файлов

```
tools/lesson-viewer/
  settings.gradle.kts                          # самостоятельная сборка
  build.gradle.kts                             # kotlin jvm + compose + application
  gradle.properties
  src/main/kotlin/lessonviewer/
    Main.kt                                    # main(), окно, состояние выбора
    model/Lesson.kt                            # Lesson, LessonStep и вложенные @Serializable
    model/LessonAnswers.kt                     # чистые функции: правильные ответы и порядок
    io/LessonLoader.kt                         # поиск каталога, чтение и разбор файлов
    io/LessonsDir.kt                           # разрешение пути к каталогу уроков
    ui/LessonListPane.kt                       # левая панель: список уроков
    ui/LessonPane.kt                           # правая панель: прокрутка шагов
    ui/StepCard.kt                             # диспетчер по type + плашка неизвестного типа
    ui/steps/TextSteps.kt                      # text, profile, persona, language
    ui/steps/AnswerSteps.kt                    # quiz, scenario, reveal, concept, example, fill
    ui/steps/OrderSteps.kt                     # sort, match
    ui/steps/ImageStep.kt                      # image + SVG + плашка ошибки
    ui/Plates.kt                               # ErrorPlate, TypeBadge — общие мелочи
  src/test/kotlin/lessonviewer/
    LessonAnswersTest.kt                       # чистая логика ответов
    AllLessonsParseTest.kt                     # все уроки разбираются, типы известны
```

Разделение по ответственности, а не по слою: файлы шагов сгруппированы по тому, как шаг устроен (текст / есть правильный ответ / есть порядок / картинка), потому что меняться они будут вместе именно так.

---

### Task 1: Сборка и пустое окно

Задача-разведка. Её цель — узнать про несовместимость версий в первые полчаса, а не после того, как написан весь UI. Пока окно не открылось, дальше идти нельзя.

**Files:**
- Create: `tools/lesson-viewer/settings.gradle.kts`
- Create: `tools/lesson-viewer/build.gradle.kts`
- Create: `tools/lesson-viewer/gradle.properties`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt`
- Create: `tools/lesson-viewer/.gitignore`

**Interfaces:**
- Consumes: ничего.
- Produces: рабочая команда `./gradlew -p tools/lesson-viewer run`, открывающая окно. Пакет всего кода — `lessonviewer`.

- [ ] **Step 1: Создать `tools/lesson-viewer/settings.gradle.kts`**

```kotlin
pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

rootProject.name = "lesson-viewer"
```

Заметь: `dependencyResolutionManagement` здесь свой. Эта сборка не наследует репозитории корневого проекта — она про него вообще не знает.

- [ ] **Step 2: Создать `tools/lesson-viewer/build.gradle.kts`**

```kotlin
plugins {
  kotlin("jvm") version "2.1.0"
  kotlin("plugin.serialization") version "2.1.0"
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
  id("org.jetbrains.compose") version "1.7.3"
  application
}

kotlin { jvmToolchain(17) }

dependencies {
  implementation(compose.desktop.currentOs)
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  testImplementation(kotlin("test"))
  testImplementation("junit:junit:4.13.2")
}

application {
  mainClass.set("lessonviewer.MainKt")
}

tasks.test { useJUnit() }
```

Версии Kotlin 2.1.0 и Compose 1.7.3 взяты как заведомо совместимая пара и НЕ обязаны совпадать с Kotlin 2.3.0 из `:app` — сборки независимы, общего кода нет.

- [ ] **Step 3: Создать `tools/lesson-viewer/gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2g
kotlin.code.style=official
```

- [ ] **Step 4: Создать `tools/lesson-viewer/.gitignore`**

```gitignore
build/
.gradle/
```

- [ ] **Step 5: Создать `tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt`**

```kotlin
package lessonviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Уроки",
    state = rememberWindowState(size = DpSize(1280.dp, 900.dp)),
  ) {
    MaterialTheme {
      Surface { Text("окно живо") }
    }
  }
}
```

- [ ] **Step 6: Запустить и убедиться, что окно открывается**

Run: `./gradlew -p tools/lesson-viewer run`
Expected: открывается окно 1280×900 с надписью «окно живо».

**Если сборка упала — не чинить наугад, а прочитать сообщение:**

- *«plugin requires Kotlin X» / несовпадение версии Compose-компилятора* — в сообщении названа поддерживаемая версия. Выставить её всем трём Kotlin-плагинам (`jvm`, `plugin.serialization`, `plugin.compose`) одним значением.
- *Ошибка совместимости с Gradle* (корневой wrapper — 9.3.1, а Compose 1.7.3 мог его не застать): завести вьюеру собственный wrapper на Gradle 8.11 и запускать его им, а не корневым:
  `cd tools/lesson-viewer && gradle wrapper --gradle-version 8.11` — после чего команда запуска становится `./tools/lesson-viewer/gradlew -p tools/lesson-viewer run`.
- *Артефакты не резолвятся* — проверить, что оба репозитория из Step 1 на месте, включая `maven.pkg.jetbrains.space`.

Какой бы вариант ни сработал, записать итоговую команду запуска в Task 7 (README).

- [ ] **Step 7: Коммит**

```bash
git add tools/lesson-viewer/settings.gradle.kts tools/lesson-viewer/build.gradle.kts tools/lesson-viewer/gradle.properties tools/lesson-viewer/.gitignore tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt
git commit -m "feat(tools): скелет десктопной читалки уроков"
```

---

### Task 2: Модель урока, загрузка с диска и тест на всех уроках

**Files:**
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/model/Lesson.kt`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/io/LessonsDir.kt`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/io/LessonLoader.kt`
- Test: `tools/lesson-viewer/src/test/kotlin/lessonviewer/AllLessonsParseTest.kt`

**Interfaces:**
- Consumes: пакет `lessonviewer` из Task 1.
- Produces:
  - `data class Lesson(id, title, description, steps: List<LessonStep>)`
  - `data class LessonStep(type, …)` — все поля с дефолтами
  - `object LessonsDir { fun resolve(): File?; const val PROPERTY = "lessons.dir"; val DEFAULT_RELATIVE = "../../app/src/main/assets/lessons" }`
  - `sealed interface LoadedLesson { data class Ok(val file: File, val lesson: Lesson); data class Failed(val file: File, val message: String) }`
  - `object LessonLoader { fun loadAll(dir: File): List<LoadedLesson> }`
  - `val KNOWN_STEP_TYPES: Set<String>`

- [ ] **Step 1: Написать падающий тест**

Create `tools/lesson-viewer/src/test/kotlin/lessonviewer/AllLessonsParseTest.kt`:

```kotlin
package lessonviewer

import lessonviewer.io.LessonLoader
import lessonviewer.io.LessonsDir
import lessonviewer.io.LoadedLesson
import lessonviewer.model.KNOWN_STEP_TYPES
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test

class AllLessonsParseTest {

  private fun dir() = LessonsDir.resolve()

  @Test
  fun `каждый урок разбирается`() {
    val d = dir()
    assumeNotNull("каталог уроков не найден — тест пропущен", d)
    val failed = LessonLoader.loadAll(d!!).filterIsInstance<LoadedLesson.Failed>()
    assertTrue(
      "не разобрались: " + failed.joinToString { "${it.file.name}: ${it.message}" },
      failed.isEmpty(),
    )
  }

  @Test
  fun `все типы шагов известны вьюеру`() {
    val d = dir()
    assumeNotNull("каталог уроков не найден — тест пропущен", d)
    val unknown = LessonLoader.loadAll(d!!)
      .filterIsInstance<LoadedLesson.Ok>()
      .flatMap { ok -> ok.lesson.steps.map { ok.file.name to it.type } }
      .filter { it.second !in KNOWN_STEP_TYPES }
      .distinct()
    assertTrue(
      "неизвестные типы шагов (модель вьюера отстала от :app?): $unknown",
      unknown.isEmpty(),
    )
  }

  @Test
  fun `уроки вообще нашлись`() {
    val d = dir()
    assumeNotNull("каталог уроков не найден — тест пропущен", d)
    assertTrue("в каталоге нет ни одного урока", LessonLoader.loadAll(d!!).isNotEmpty())
  }
}
```

`assumeNotNull` — чтобы тест не краснел у того, кто запускает сборку из другого места; отсутствие каталога это пропуск, а не провал.

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew -p tools/lesson-viewer test`
Expected: FAIL — компиляция не проходит, `Unresolved reference: LessonLoader`.

- [ ] **Step 3: Написать модель**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/model/Lesson.kt`:

```kotlin
package lessonviewer.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Калька формата `app/src/main/assets/lessons/*.json`. Модель СОЗНАТЕЛЬНО продублирована, а не
 * подтянута из :app — см. решение 2.1 спеки. Расхождение ловится [lessonviewer.AllLessonsParseTest].
 */
@Serializable
data class Lesson(
  val id: String = "",
  val title: String = "",
  val description: String = "",
  val steps: List<LessonStep> = emptyList(),
)

/** Плоский шаг: дискриминатор [type], все остальные поля опциональны — как в :app. */
@Serializable
data class LessonStep(
  val type: String = "",
  // text и служебные
  val text: String = "",
  val note: String = "",
  // quiz
  val questions: List<QuizQuestion> = emptyList(),
  // image
  val asset: String = "",
  val url: String = "",
  val caption: String = "",
  val imageKey: String = "",
  val alt: String = "",
  // reveal / fill
  val question: String = "",
  val answer: String = "",
  val template: String = "",
  val caseSensitive: Boolean = false,
  // concept
  val term: String = "",
  val definition: String = "",
  val example: String = "",
  // example
  val label: String = "",
  val parts: List<ExamplePart> = emptyList(),
  // sort / match
  val instruction: String = "",
  val items: List<String> = emptyList(),
  val pairs: List<MatchPair> = emptyList(),
  // scenario
  val situation: String = "",
  val q: String = "",
  val options: List<String> = emptyList(),
  /** Полиморфно: у "sort" — массив индексов, у "scenario" — одно число. Разбор — в LessonAnswers. */
  val correct: JsonElement? = null,
  val explain: String = "",
  // input / challenge — в уроках пока не встречаются, поля держим, чтобы не падать
  val prompt: String = "",
  val hint: String = "",
  val task: String = "",
)

@Serializable
data class QuizQuestion(
  val q: String = "",
  val options: List<String> = emptyList(),
  /** -1, а не 0: при отсутствующем/битом ключе НЕ помечаем первый вариант правильным. */
  val correct: Int = -1,
  val explain: String = "",
)

@Serializable
data class ExamplePart(
  val text: String = "",
  val role: String = "",
  val note: String = "",
)

@Serializable
data class MatchPair(
  val term: String = "",
  val def: String = "",
)

/** Типы, которые вьюер умеет рисовать. Незнакомый тип рисуется красной плашкой, а не пропускается. */
val KNOWN_STEP_TYPES: Set<String> = setOf(
  "text", "quiz", "scenario", "reveal", "image", "concept",
  "match", "sort", "fill", "example",
  "profile", "persona", "language",
  "input", "challenge",
)
```

- [ ] **Step 4: Написать разрешение пути к каталогу**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/io/LessonsDir.kt`:

```kotlin
package lessonviewer.io

import java.io.File

/**
 * Где лежат уроки. По умолчанию — относительно рабочего каталога процесса: при `gradlew -p
 * tools/lesson-viewer run` это сам `tools/lesson-viewer/`, поэтому два уровня вверх дают корень репо.
 */
object LessonsDir {
  const val PROPERTY = "lessons.dir"
  const val DEFAULT_RELATIVE = "../../app/src/main/assets/lessons"

  /** Каталог с уроками, либо null — вызывающий показывает подсказку, а не падает. */
  fun resolve(): File? {
    val override = System.getProperty(PROPERTY)
    val candidate = if (override.isNullOrBlank()) File(DEFAULT_RELATIVE) else File(override)
    return candidate.takeIf { it.isDirectory }?.absoluteFile
  }

  /** Путь, который искали, — для сообщения об ошибке. */
  fun expectedPath(): String =
    (System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT_RELATIVE)
      .let { File(it).absolutePath }
}
```

- [ ] **Step 5: Написать загрузчик**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/io/LessonLoader.kt`:

```kotlin
package lessonviewer.io

import kotlinx.serialization.json.Json
import lessonviewer.model.Lesson
import java.io.File

/** Один файл урока: либо разобран, либо нет. Битый файл НЕ прячем — показываем причину. */
sealed interface LoadedLesson {
  val file: File

  data class Ok(override val file: File, val lesson: Lesson) : LoadedLesson
  data class Failed(override val file: File, val message: String) : LoadedLesson
}

object LessonLoader {
  private val json = Json { ignoreUnknownKeys = true }

  /** Все `*.json` каталога, отсортированные по имени файла. Порядок ROADMAP_LEVELS не копируем. */
  fun loadAll(dir: File): List<LoadedLesson> =
    (dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) } ?: emptyArray())
      .sortedBy { it.name }
      .map { load(it) }

  fun load(file: File): LoadedLesson = try {
    // readText() — UTF-8 по умолчанию. НЕ заменять на defaultCharset(): системная кодировка
    // на машине разработчика не UTF-8, уроки поедут кракозябрами.
    LoadedLesson.Ok(file, json.decodeFromString(Lesson.serializer(), file.readText()))
  } catch (e: Exception) {
    LoadedLesson.Failed(file, e.message ?: e::class.simpleName ?: "ошибка разбора")
  }
}
```

- [ ] **Step 6: Запустить тест и убедиться, что он проходит**

Run: `./gradlew -p tools/lesson-viewer test`
Expected: PASS, три теста.

Если `все типы шагов известны вьюеру` упал — в уроке появился тип, которого нет в `KNOWN_STEP_TYPES`. Это не повод расширить множество молча: сначала посмотреть, что это за шаг, и дорисовать ему рендер в Task 5.

- [ ] **Step 7: Коммит**

```bash
git add tools/lesson-viewer/src/main/kotlin/lessonviewer/model/Lesson.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/io/LessonsDir.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/io/LessonLoader.kt tools/lesson-viewer/src/test/kotlin/lessonviewer/AllLessonsParseTest.kt
git commit -m "feat(tools): модель урока и чтение уроков с диска"
```

---

### Task 3: Чистые функции ответов

Здесь живёт единственная нетривиальная логика вьюера, и именно она может соврать: показать неверный «правильный» ответ. Поэтому TDD.

**Files:**
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/model/LessonAnswers.kt`
- Test: `tools/lesson-viewer/src/test/kotlin/lessonviewer/LessonAnswersTest.kt`

**Interfaces:**
- Consumes: `LessonStep`, `QuizQuestion` из Task 2.
- Produces:
  - `fun LessonStep.scenarioCorrectIndex(): Int` — 0-based, `-1` если ключа нет или он битый
  - `fun LessonStep.sortCorrectOrder(): List<String>?` — элементы в правильном порядке, либо `null` если `correct` не полная перестановка
  - `fun QuizQuestion.hasCorrect(): Boolean`

- [ ] **Step 1: Написать падающий тест**

Create `tools/lesson-viewer/src/test/kotlin/lessonviewer/LessonAnswersTest.kt`:

```kotlin
package lessonviewer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import lessonviewer.model.LessonStep
import lessonviewer.model.QuizQuestion
import lessonviewer.model.hasCorrect
import lessonviewer.model.scenarioCorrectIndex
import lessonviewer.model.sortCorrectOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonAnswersTest {

  private fun arr(vararg v: Int) = Json.parseToJsonElement(v.joinToString(",", "[", "]"))

  // --- scenario ---

  @Test
  fun `scenario берёт индекс из числа`() {
    val step = LessonStep(type = "scenario", correct = JsonPrimitive(2))
    assertEquals(2, step.scenarioCorrectIndex())
  }

  @Test
  fun `scenario без ключа даёт минус один, а не ноль`() {
    val step = LessonStep(type = "scenario", correct = null)
    assertEquals(-1, step.scenarioCorrectIndex())
  }

  @Test
  fun `scenario с массивом вместо числа даёт минус один`() {
    val step = LessonStep(type = "scenario", correct = arr(0, 1))
    assertEquals(-1, step.scenarioCorrectIndex())
  }

  // --- sort ---

  @Test
  fun `sort раскладывает элементы по перестановке`() {
    // Реальный шаг из agent_skills.json: correct = [1,2,0]
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(1, 2, 0))
    assertEquals(listOf("б", "в", "а"), step.sortCorrectOrder())
  }

  @Test
  fun `sort с неполной перестановкой не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(0, 1))
    assertNull(step.sortCorrectOrder())
  }

  @Test
  fun `sort с дублями не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(0, 0, 1))
    assertNull(step.sortCorrectOrder())
  }

  @Test
  fun `sort с индексом вне диапазона не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б", "в"), correct = arr(0, 1, 7))
    assertNull(step.sortCorrectOrder())
  }

  @Test
  fun `sort без ключа не выдумывает порядок`() {
    val step = LessonStep(type = "sort", items = listOf("а", "б"), correct = null)
    assertNull(step.sortCorrectOrder())
  }

  // --- quiz ---

  @Test
  fun `quiz с корректным индексом имеет ответ`() {
    assertTrue(QuizQuestion(q = "?", options = listOf("а", "б"), correct = 1).hasCorrect())
  }

  @Test
  fun `quiz с минус единицей ответа не имеет`() {
    assertFalse(QuizQuestion(q = "?", options = listOf("а", "б"), correct = -1).hasCorrect())
  }

  @Test
  fun `quiz с индексом вне диапазона ответа не имеет`() {
    assertFalse(QuizQuestion(q = "?", options = listOf("а", "б"), correct = 5).hasCorrect())
  }
}
```

- [ ] **Step 2: Запустить тест и убедиться, что он падает**

Run: `./gradlew -p tools/lesson-viewer test --tests '*LessonAnswersTest*'`
Expected: FAIL — `Unresolved reference: scenarioCorrectIndex`.

- [ ] **Step 3: Написать реализацию**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/model/LessonAnswers.kt`:

```kotlin
package lessonviewer.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Разбор «правильных ответов». Общее правило: битый или отсутствующий ключ НИКОГДА не превращается
 * в «правильный — первый». Молча подсвеченный не тот вариант хуже, чем честно не подсвеченный
 * ни один (в :app тот же класс бага чинил коммит f646e64).
 */

/** "scenario": 0-based индекс верного варианта, либо -1 если ключа нет или он не число. */
fun LessonStep.scenarioCorrectIndex(): Int = (correct as? JsonPrimitive)?.intOrNull ?: -1

/**
 * "sort": элементы в правильном порядке, либо null, если [LessonStep.correct] не является полной
 * перестановкой индексов [LessonStep.items].
 *
 * Семантика перестановки взята из приложения: `correct[позиция] = индекс элемента`, то есть
 * правильный порядок — `correct.map { items[it] }`. Источник — SortCardView в
 * app/src/main/java/com/aiopenpocket/ui/chat/components/DrivenUiCardViews.kt (около строки 1124),
 * там же и проверка на полную перестановку.
 */
fun LessonStep.sortCorrectOrder(): List<String>? {
  val indices = (correct as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: return null
  val n = items.size
  val isPermutation = indices.size == n && indices.toSet() == (0 until n).toSet()
  return if (isPermutation) indices.map { items[it] } else null
}

/** У вопроса квиза есть отмеченный правильный вариант и он в диапазоне. */
fun QuizQuestion.hasCorrect(): Boolean = correct in options.indices
```

- [ ] **Step 4: Запустить тест и убедиться, что он проходит**

Run: `./gradlew -p tools/lesson-viewer test`
Expected: PASS, все тесты обоих классов.

- [ ] **Step 5: Коммит**

```bash
git add tools/lesson-viewer/src/main/kotlin/lessonviewer/model/LessonAnswers.kt tools/lesson-viewer/src/test/kotlin/lessonviewer/LessonAnswersTest.kt
git commit -m "feat(tools): разбор правильных ответов уроков"
```

---

### Task 4: Каркас окна — список уроков, прокрутка, «Перечитать», ошибки

Гейт визуальный: тестов на Compose-UI нет по решению спеки (раздел 8).

**Files:**
- Modify: `tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt` (переписывается целиком)
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonListPane.kt`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonPane.kt`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/Plates.kt`

**Interfaces:**
- Consumes: `LessonLoader.loadAll`, `LessonsDir.resolve/expectedPath`, `LoadedLesson` (Task 2).
- Produces:
  - `@Composable fun ErrorPlate(text: String)` — красная плашка
  - `@Composable fun TypeBadge(type: String, index: Int)`
  - `@Composable fun LessonListPane(items: List<LoadedLesson>, selected: LoadedLesson?, onSelect: (LoadedLesson) -> Unit)`
  - `@Composable fun LessonPane(loaded: LoadedLesson?)` — в Task 5 внутрь встанет `StepCard`

- [ ] **Step 1: Написать общие плашки**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/Plates.kt`:

```kotlin
package lessonviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Красная плашка: то, что в приложении молча превращается в пустоту, тут должно быть видно. */
@Composable
fun ErrorPlate(text: String) {
  Box(
    Modifier
      .fillMaxWidth()
      .background(Color(0x22FF0000), RoundedCornerShape(6.dp))
      .padding(10.dp),
  ) {
    Text(text, color = Color(0xFFB00020), style = MaterialTheme.typography.bodySmall)
  }
}

/** Номер шага и его тип — чтобы можно было назвать шаг вслух, сверяясь с приложением. */
@Composable
fun TypeBadge(type: String, index: Int) {
  Box(
    Modifier
      .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(4.dp))
      .padding(horizontal = 6.dp, vertical = 2.dp),
  ) {
    Text("#$index · $type", style = MaterialTheme.typography.labelSmall)
  }
}
```

- [ ] **Step 2: Написать левую панель**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonListPane.kt`:

```kotlin
package lessonviewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import lessonviewer.io.LoadedLesson

@Composable
fun LessonListPane(
  items: List<LoadedLesson>,
  selected: LoadedLesson?,
  onSelect: (LoadedLesson) -> Unit,
) {
  LazyColumn(Modifier.fillMaxSize().padding(8.dp)) {
    items(items) { item ->
      val isSelected = item.file == selected?.file
      Column(
        Modifier
          .fillMaxWidth()
          .background(
            if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
          )
          .clickable { onSelect(item) }
          .padding(8.dp),
      ) {
        Text(item.file.name, style = MaterialTheme.typography.labelSmall)
        when (item) {
          is LoadedLesson.Ok ->
            Text(
              item.lesson.title.ifBlank { "(без заголовка)" },
              style = MaterialTheme.typography.bodyMedium,
            )
          is LoadedLesson.Failed ->
            Text("не разбирается", color = Color(0xFFB00020), style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}
```

- [ ] **Step 3: Написать правую панель**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonPane.kt`:

```kotlin
package lessonviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lessonviewer.io.LoadedLesson

@Composable
fun LessonPane(loaded: LoadedLesson?) {
  when (loaded) {
    null -> Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Выбери урок слева") }

    is LoadedLesson.Failed -> Column(Modifier.fillMaxSize().padding(16.dp)) {
      Text(loaded.file.name, style = MaterialTheme.typography.titleMedium)
      Spacer(Modifier.height(8.dp))
      ErrorPlate("Урок не разбирается: ${loaded.message}")
    }

    is LoadedLesson.Ok -> LazyColumn(
      Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item {
        Column {
          Text(loaded.lesson.title.ifBlank { loaded.file.name }, style = MaterialTheme.typography.headlineSmall)
          if (loaded.lesson.description.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(loaded.lesson.description, style = MaterialTheme.typography.bodyMedium)
          }
          Spacer(Modifier.height(4.dp))
          Text("шагов: ${loaded.lesson.steps.size}", style = MaterialTheme.typography.labelSmall)
        }
      }
      itemsIndexed(loaded.lesson.steps) { i, step ->
        // Заглушка до Task 5: показываем тип и номер, чтобы каркас был проверяем уже сейчас.
        Column {
          TypeBadge(step.type, i)
          Spacer(Modifier.height(4.dp))
          Text(step.text.ifBlank { "(рендер шага — Task 5)" }, style = MaterialTheme.typography.bodyMedium)
        }
      }
    }
  }
}
```

Нужен импорт `androidx.compose.ui.Alignment` — добавить его в список импортов.

- [ ] **Step 4: Переписать `Main.kt`**

Replace `tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt`:

```kotlin
package lessonviewer

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import lessonviewer.io.LessonLoader
import lessonviewer.io.LessonsDir
import lessonviewer.io.LoadedLesson
import lessonviewer.ui.ErrorPlate
import lessonviewer.ui.LessonListPane
import lessonviewer.ui.LessonPane

fun main() = application {
  Window(
    onCloseRequest = ::exitApplication,
    title = "Уроки",
    state = rememberWindowState(size = DpSize(1280.dp, 900.dp)),
  ) {
    MaterialTheme { ViewerRoot() }
  }
}

@Composable
private fun ViewerRoot() {
  var reloadTick by remember { mutableStateOf(0) }
  var selectedName by remember { mutableStateOf<String?>(null) }

  // reloadTick в ключе — этим и перечитываем: пересчитать список и заново разобрать файлы.
  val dir = remember(reloadTick) { LessonsDir.resolve() }
  val lessons = remember(reloadTick, dir) { dir?.let { LessonLoader.loadAll(it) } ?: emptyList() }
  // Выбор храним по имени файла, а не по объекту: после перечитывания объекты новые.
  val selected = lessons.firstOrNull { it.file.name == selectedName }

  Column(Modifier.fillMaxSize()) {
    Row(
      Modifier.fillMaxWidth().padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Button(onClick = { reloadTick++ }) { Text("Перечитать") }
      Text(
        dir?.path ?: "каталог не найден",
        style = MaterialTheme.typography.labelSmall,
      )
    }
    HorizontalDivider()

    if (dir == null) {
      Column(Modifier.fillMaxSize().padding(16.dp)) {
        ErrorPlate(
          "Каталог уроков не найден.\nИскал: ${LessonsDir.expectedPath()}\n" +
            "Запусти из tools/lesson-viewer или задай -D${LessonsDir.PROPERTY}=<путь>",
        )
      }
    } else {
      Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(300.dp).fillMaxHeight()) {
          LessonListPane(lessons, selected) { selectedName = it.file.name }
        }
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) { LessonPane(selected) }
      }
    }
  }
}
```

- [ ] **Step 5: Запустить и посмотреть глазами**

Run: `./gradlew -p tools/lesson-viewer run`

Проверить:
1. Слева — 38 уроков, отсортированных по имени файла, у каждого имя файла и заголовок.
2. Клик по уроку показывает справа заголовок, описание и число шагов, дальше — плашки `#N · type`.
3. Кнопка «Перечитать» не роняет окно и сохраняет выбранный урок.
4. Правка `title` в любом уроке + «Перечитать» → новый заголовок виден **без пересборки**. Это и есть смысл всей затеи; если не работает — чинить здесь, а не идти дальше.
5. Запуск с несуществующим каталогом показывает подсказку, а не падает:
   `./gradlew -p tools/lesson-viewer run -Dlessons.dir=Z:\нет`
   (если системное свойство не долетает до приложения, добавить в `build.gradle.kts`:
   `tasks.named<JavaExec>("run") { systemProperty("lessons.dir", System.getProperty("lessons.dir") ?: "") }`
   — и тогда пустое значение в `LessonsDir.resolve()` уже трактуется как «не задано»)

- [ ] **Step 6: Коммит**

```bash
git add tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/Plates.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonListPane.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonPane.kt
git commit -m "feat(tools): каркас читалки — список уроков, прокрутка, перечитывание"
```

---

### Task 5: Рендер всех типов шагов

**Files:**
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/StepCard.kt`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/TextSteps.kt`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/AnswerSteps.kt`
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/OrderSteps.kt`
- Modify: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonPane.kt` — заменить заглушку из Task 4 Step 3 на `StepCard(step, i)`

**Interfaces:**
- Consumes: `LessonStep`, `KNOWN_STEP_TYPES`, `scenarioCorrectIndex()`, `sortCorrectOrder()`, `hasCorrect()`, `TypeBadge`, `ErrorPlate`.
- Produces: `@Composable fun StepCard(step: LessonStep, index: Int)` — единственная точка входа рендера шага; `ImageStepView` подключается к нему в Task 6.

- [ ] **Step 1: Написать диспетчер**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/StepCard.kt`:

```kotlin
package lessonviewer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.ui.steps.*

@Composable
fun StepCard(step: LessonStep, index: Int) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      TypeBadge(step.type, index)
      when (step.type) {
        "text", "profile", "persona", "language", "input", "challenge" -> TextStepView(step)
        "quiz" -> QuizStepView(step)
        "scenario" -> ScenarioStepView(step)
        "reveal" -> RevealStepView(step)
        "concept" -> ConceptStepView(step)
        "example" -> ExampleStepView(step)
        "fill" -> FillStepView(step)
        "sort" -> SortStepView(step)
        "match" -> MatchStepView(step)
        "image" -> ErrorPlate("рендер картинок — Task 6")
        else -> ErrorPlate("неизвестный тип шага: «${step.type}»")
      }
    }
  }
}
```

- [ ] **Step 2: Написать текстовые шаги**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/TextSteps.kt`:

```kotlin
package lessonviewer.ui.steps

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep

/** text и служебные шаги онбординга (profile/persona/language), а также ещё не живые input/challenge. */
@Composable
fun TextStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val body = listOf(step.text, step.prompt, step.task, step.hint)
      .filter { it.isNotBlank() }
    if (body.isEmpty()) {
      Text("(пустой шаг)", style = MaterialTheme.typography.bodySmall)
    } else {
      body.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
    if (step.note.isNotBlank()) {
      Text(step.note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
    }
  }
}
```

- [ ] **Step 3: Написать шаги с ответами**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/AnswerSteps.kt`:

```kotlin
package lessonviewer.ui.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.model.hasCorrect
import lessonviewer.model.scenarioCorrectIndex
import lessonviewer.ui.ErrorPlate

private val CorrectBg = Color(0x2200AA00)

@Composable
private fun Option(text: String, correct: Boolean) {
  Box(
    Modifier
      .fillMaxWidth()
      .background(if (correct) CorrectBg else Color.Transparent, RoundedCornerShape(4.dp))
      .padding(horizontal = 8.dp, vertical = 4.dp),
  ) {
    Text(
      (if (correct) "✓ " else "· ") + text,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = if (correct) FontWeight.SemiBold else FontWeight.Normal,
    )
  }
}

@Composable
private fun Explain(text: String) {
  if (text.isNotBlank()) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
  }
}

@Composable
fun QuizStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    step.questions.forEach { q ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(q.q, style = MaterialTheme.typography.titleSmall)
        q.options.forEachIndexed { i, opt -> Option(opt, correct = q.hasCorrect() && i == q.correct) }
        if (!q.hasCorrect()) ErrorPlate("у вопроса нет корректного `correct` (${q.correct})")
        Explain(q.explain)
      }
    }
  }
}

@Composable
fun ScenarioStepView(step: LessonStep) {
  val correctIndex = step.scenarioCorrectIndex()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (step.situation.isNotBlank()) Text(step.situation, style = MaterialTheme.typography.bodyLarge)
    if (step.q.isNotBlank()) Text(step.q, style = MaterialTheme.typography.titleSmall)
    step.options.forEachIndexed { i, opt -> Option(opt, correct = i == correctIndex) }
    if (correctIndex !in step.options.indices) ErrorPlate("`correct` отсутствует или битый ($correctIndex)")
    Explain(step.explain)
  }
}

@Composable
fun RevealStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(step.question, style = MaterialTheme.typography.titleSmall)
    Text(step.answer, style = MaterialTheme.typography.bodyLarge)
  }
}

@Composable
fun ConceptStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(step.term, style = MaterialTheme.typography.titleSmall)
    Text(step.definition, style = MaterialTheme.typography.bodyLarge)
    if (step.example.isNotBlank()) Text(step.example, style = MaterialTheme.typography.bodySmall)
  }
}

@Composable
fun ExampleStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    if (step.label.isNotBlank()) Text(step.label, style = MaterialTheme.typography.titleSmall)
    step.parts.forEach { part ->
      Column {
        Text(part.text, style = MaterialTheme.typography.bodyMedium)
        val meta = listOf(part.role, part.note).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
fun FillStepView(step: LessonStep) {
  // Подставляем ответ прямо в пропуск: вьюер для вычитки, а не для прохождения.
  val filled = if (step.template.contains("___")) {
    step.template.replace("___", "【${step.answer}】")
  } else {
    step.template
  }
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(filled, style = MaterialTheme.typography.bodyLarge)
    if (!step.template.contains("___")) {
      ErrorPlate("в `template` нет пропуска `___`, ответ: «${step.answer}»")
    }
  }
}
```

- [ ] **Step 4: Написать шаги с порядком**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/OrderSteps.kt`:

```kotlin
package lessonviewer.ui.steps

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.model.sortCorrectOrder
import lessonviewer.ui.ErrorPlate

@Composable
fun SortStepView(step: LessonStep) {
  val ordered = step.sortCorrectOrder()
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (step.instruction.isNotBlank()) Text(step.instruction, style = MaterialTheme.typography.titleSmall)
    if (ordered != null) {
      ordered.forEachIndexed { i, item ->
        Text("${i + 1}. $item", style = MaterialTheme.typography.bodyMedium)
      }
    } else {
      // Порядок не восстанавливается — показываем авторский и честно говорим об этом,
      // а не выдумываем «правильный».
      step.items.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
      ErrorPlate("`correct` не полная перестановка — показан авторский порядок: ${step.correct}")
    }
    if (step.explain.isNotBlank()) {
      Text(step.explain, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
  }
}

@Composable
fun MatchStepView(step: LessonStep) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (step.instruction.isNotBlank()) Text(step.instruction, style = MaterialTheme.typography.titleSmall)
    step.pairs.forEach { pair ->
      Text("${pair.term} → ${pair.def}", style = MaterialTheme.typography.bodyMedium)
    }
    if (step.pairs.isEmpty()) ErrorPlate("у шага match нет пар")
  }
}
```

Нужен импорт `androidx.compose.ui.Modifier` — добавить.

- [ ] **Step 5: Подключить `StepCard` в `LessonPane`**

В `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonPane.kt` заменить тело `itemsIndexed` (заглушка из Task 4) на:

```kotlin
      itemsIndexed(loaded.lesson.steps) { i, step ->
        StepCard(step, i)
      }
```

и убрать ставшие ненужными импорты `TypeBadge`/`Spacer`, если они больше нигде в файле не используются.

- [ ] **Step 6: Запустить и пройтись по урокам**

Run: `./gradlew -p tools/lesson-viewer run`

Проверить на конкретных уроках:
1. `agent_skills.json`, шаг 9 — `sort` с `correct: [1,2,0]` показывает порядок «второй, третий, первый» относительно авторского списка.
2. Любой урок с `quiz` — правильный вариант подсвечен зелёным и помечен `✓`, под ним `explain`.
3. Любой урок с `scenario` — то же самое.
4. `tokens_and_speed.json` или другой урок с `concept`/`reveal` — термины и ответы читаются.
5. Красных плашек «неизвестный тип шага» быть не должно ни в одном уроке — все 13 типов покрыты. Если плашка появилась, это новый тип: дорисовать ему рендер и добавить в `KNOWN_STEP_TYPES`.

- [ ] **Step 7: Прогнать тесты — модель не должна была сломаться**

Run: `./gradlew -p tools/lesson-viewer test`
Expected: PASS.

- [ ] **Step 8: Коммит**

```bash
git add tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/StepCard.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/ tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonPane.kt
git commit -m "feat(tools): рендер всех типов шагов урока"
```

---

### Task 6: Картинки уроков

**Files:**
- Create: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/ImageStep.kt`
- Modify: `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/StepCard.kt` — ветка `"image"`
- Modify: `tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt` — прокинуть корень ассетов

**Interfaces:**
- Consumes: `LessonsDir.resolve()` (Task 2), `ErrorPlate` (Task 4).
- Produces: `@Composable fun ImageStepView(step: LessonStep, assetsRoot: File?)`.

Шаги `image` ссылаются на ассеты путями вида `lessons/images/llm_path.svg`, то есть относительно **`app/src/main/assets/`** — на уровень выше каталога уроков.

- [ ] **Step 1: Написать рендер картинки**

Create `tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/ImageStep.kt`:

```kotlin
package lessonviewer.ui.steps

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import lessonviewer.model.LessonStep
import lessonviewer.ui.ErrorPlate
import java.io.File

@Composable
fun ImageStepView(step: LessonStep, assetsRoot: File?) {
  // imageKey — сокращение для lessons/images/{imageKey}.svg; иначе берём asset как есть.
  val relative = when {
    step.imageKey.isNotBlank() -> "lessons/images/${step.imageKey}.svg"
    step.asset.isNotBlank() -> step.asset
    else -> ""
  }
  val file = if (relative.isBlank() || assetsRoot == null) null else File(assetsRoot, relative)
  val density = LocalDensity.current

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    when {
      relative.isBlank() && step.url.isNotBlank() ->
        ErrorPlate("картинка только по сети, вьюер её не грузит: ${step.url}")

      file == null ->
        ErrorPlate("у шага image нет ни asset, ни imageKey")

      !file.isFile ->
        ErrorPlate("картинка не найдена: ${file.path}")

      !file.name.endsWith(".svg", ignoreCase = true) ->
        ErrorPlate("поддерживается только SVG, а тут: ${file.name}")

      else -> {
        val painter = remember(file.path) {
          runCatching { file.inputStream().use { loadSvgPainter(it, density) } }.getOrNull()
        }
        if (painter == null) {
          ErrorPlate("SVG не рисуется: ${file.path}")
        } else {
          Image(painter, contentDescription = step.alt, modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp))
        }
      }
    }
    val caption = step.caption.ifBlank { step.alt }
    if (caption.isNotBlank()) Text(caption, style = MaterialTheme.typography.bodySmall)
  }
}
```

- [ ] **Step 2: Прокинуть корень ассетов**

В `StepCard.kt` изменить сигнатуру и ветку `"image"`:

```kotlin
@Composable
fun StepCard(step: LessonStep, index: Int, assetsRoot: File?) {
```

```kotlin
        "image" -> ImageStepView(step, assetsRoot)
```

добавив импорт `java.io.File`.

В `LessonPane.kt` — так же протащить параметр:

```kotlin
@Composable
fun LessonPane(loaded: LoadedLesson?, assetsRoot: File?) {
```

```kotlin
      itemsIndexed(loaded.lesson.steps) { i, step ->
        StepCard(step, i, assetsRoot)
      }
```

В `Main.kt` — посчитать корень ассетов как родителя каталога уроков и передать вниз:

```kotlin
  // Пути в уроках вида "lessons/images/x.svg" отсчитываются от app/src/main/assets/,
  // то есть на уровень выше каталога с самими уроками.
  val assetsRoot = remember(dir) { dir?.parentFile }
```

```kotlin
        Box(Modifier.weight(1f).fillMaxHeight()) { LessonPane(selected, assetsRoot) }
```

- [ ] **Step 3: Запустить и проверить все 13 картинок**

Run: `./gradlew -p tools/lesson-viewer run`

Пройтись по урокам с шагами `image` и убедиться, что каждая из 12 картинок в `app/src/main/assets/lessons/images/` либо рисуется, либо честно даёт красную плашку с путём. Плашка — приемлемый результат (риск 10.2 спеки), молчаливая пустота — нет.

- [ ] **Step 4: Коммит**

```bash
git add tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/steps/ImageStep.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/StepCard.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/ui/LessonPane.kt tools/lesson-viewer/src/main/kotlin/lessonviewer/Main.kt
git commit -m "feat(tools): картинки уроков в читалке"
```

---

### Task 7: README и финальная проверка

**Files:**
- Create: `tools/lesson-viewer/README.md`

- [ ] **Step 1: Написать README**

Create `tools/lesson-viewer/README.md`:

```markdown
# Читалка уроков

Смотреть контент `app/src/main/assets/lessons/*.json` на десктопе, не собирая приложение.

## Запуск

    ./gradlew -p tools/lesson-viewer run

Другой каталог уроков:

    ./gradlew -p tools/lesson-viewer run -Dlessons.dir=<путь>

Правишь JSON урока → кнопка «Перечитать» в окне. Пересобирать не нужно.

## Чего тут нет

Это читалка КОНТЕНТА, а не превью внешнего вида: ни glass, ни шрифта Lora, ни настоящих
карточек из приложения, ни интерактива, ни LLM. Шаги с ответами (quiz, sort, match,
scenario, fill) показаны сразу с ответом.

## Устройство

Самостоятельная Gradle-сборка: её нет в корневом `settings.gradle.kts`, она не зависит от
`:app` и не входит в traxer. Модели уроков продублированы намеренно — расхождение с
приложением ловит `AllLessonsParseTest`.

Дизайн: `docs/superpowers/specs/2026-07-25-lesson-viewer-design.md`
```

Если в Task 1 пришлось завести вьюеру собственный wrapper, заменить команды запуска на фактические.

- [ ] **Step 2: Убедиться, что корневой проект не задет**

```bash
git status --short
```

Expected: среди изменений — только `tools/lesson-viewer/**`, плюс ДВА чужих файла, которые лежали в дереве с самого начала и которые трогать нельзя:
`app/src/main/java/com/aiopenpocket/ui/agents/detail/AgentDetailScreen.kt` и `AgentDetailSections.kt`.

Ничего другого в `app/`, `gradle/`, `settings.gradle.kts`, `traceability.yaml` измениться не должно. Если изменилось — откатить.

- [ ] **Step 3: Убедиться, что Android-сборка не сломалась**

```bash
./gradlew :app:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL. Вьюер не должен был на неё повлиять никак — это проверка того, что `tools/` действительно невидим для корневой сборки.

- [ ] **Step 4: Прогнать тесты вьюера**

```bash
./gradlew -p tools/lesson-viewer test
```

Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add tools/lesson-viewer/README.md
git commit -m "docs(tools): README читалки уроков"
```

---

## Самопроверка плана

**Покрытие спеки:**

| Раздел спеки | Задача |
|---|---|
| 3. Размещение и сборка | Task 1 |
| 4. Модель данных | Task 2 |
| 5. Чтение уроков (путь, `-Dlessons.dir`, сортировка, «Перечитать») | Task 2 + Task 4 |
| 6. UI: две панели, прокрутка, плашки типов | Task 4 |
| 6. UI: рендер 13 типов, семантика `sort` | Task 3 + Task 5 |
| 6. UI: картинки, `imageKey`, отказ от `url` | Task 6 |
| 7.1 урок не разбирается | Task 2 (`LoadedLesson.Failed`) + Task 4 (панели) |
| 7.2 неизвестный тип шага | Task 5 (ветка `else`) |
| 7.3 картинка не найдена | Task 6 |
| 7.4 каталог не найден | Task 4 |
| 7.5 битый `correct` | Task 3 (логика) + Task 5 (плашки) |
| 8. `AllLessonsParseTest` | Task 2 |
| 9. Границы (чего не делаем) | зафиксировано в README, Task 7 |
| 10.1 версии Compose | Task 1 с ветвлением по ошибке |
| 10.2 `loadSvgPainter` | Task 6 |
| 10.3 расхождение модели | Task 2 (тест типов) |

**Расхождение со спекой, внесённое сознательно:** спека в разделе 6 оставляла семантику `sort.correct` неопределённой. Она выяснена до написания плана (`DrivenUiCardViews.kt:1124`) и зафиксирована в Task 3 вместе с проверкой на полную перестановку. Оговорку в спеке про «не фиксируем» можно считать закрытой.

**Согласованность имён:** `LessonsDir.resolve()`, `LessonLoader.loadAll(dir)`, `LoadedLesson.Ok/Failed`, `scenarioCorrectIndex()`, `sortCorrectOrder()`, `hasCorrect()`, `StepCard(step, index, assetsRoot)`, `LessonPane(loaded, assetsRoot)` — используются в задачах 4–6 ровно в том виде, в каком объявлены в задачах 2–3. Сигнатуры `StepCard` и `LessonPane` расширяются в Task 6 явным шагом, а не молча.
