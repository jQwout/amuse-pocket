package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import com.aiopenpocket.ui.roadmap.LIBRARY_LESSON_IDS
import com.aiopenpocket.ui.roadmap.ROADMAP_LEVELS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Контрактный линт уроков в assets/lessons. Битый урок в рантайме НЕ падает, а молча исчезает
 * (LessonRepository.getLessonOrNull глотает ошибку парса: урок пропадает из Библиотеки, уровень
 * роадмапа деградирует в легаси-чат), а «зависающий» шаг оставляет пользователя без кнопки
 * «Дальше» навсегда. Поэтому валим сборку здесь.
 *
 * Правила выведены из кода и должны меняться вместе с ним:
 *  - типы шагов и рендер карточек — LessonChatController.postStep / DrivenUiEngine;
 *  - какие шаги идут по кнопке «Дальше» — ChatScreen (needsManualAdvance);
 *  - строгость парсинга — LessonRepository (Json { ignoreUnknownKeys = true }).
 */
class LessonAssetsValidationTest {

  // Тот же конфиг, что в LessonRepository — иначе линт проверял бы не то, что видит приложение.
  private val json = Json { ignoreUnknownKeys = true }

  // Юнит-тесты Gradle запускаются из директории модуля (app/); запасной путь — корень проекта.
  private val assetsDir: File = sequenceOf(File("src/main/assets"), File("app/src/main/assets"))
    .firstOrNull { it.isDirectory }
    ?: error("Не нашёл src/main/assets; рабочая директория: ${File(".").absolutePath}")

  private val lessonFiles: List<File> = File(assetsDir, "lessons")
    .listFiles { f -> f.extension == "json" }
    ?.sortedBy { it.name }
    .orEmpty()

  /** Уроки, которые распарсились; ошибки парса проверяет отдельный тест. */
  private fun parsedLessons(): List<Pair<File, Lesson>> = lessonFiles.mapNotNull { file ->
    runCatching { file to json.decodeFromString(Lesson.serializer(), file.readText()) }.getOrNull()
  }

  @Test
  fun `lesson assets exist`() {
    assertTrue("В ${File(assetsDir, "lessons").absolutePath} нет ни одного урока", lessonFiles.isNotEmpty())
  }

  @Test
  fun `every lesson parses with the runtime serializer`() {
    val errors = lessonFiles.mapNotNull { file ->
      runCatching { json.decodeFromString(Lesson.serializer(), file.readText()) }
        .exceptionOrNull()
        ?.let { "${file.name}: ${it.message?.lineSequence()?.firstOrNull()}" }
    }
    assertTrue(
      "Уроки не парсятся (в приложении они МОЛЧА исчезнут):\n${errors.joinToString("\n")}",
      errors.isEmpty(),
    )
  }

  @Test
  fun `lesson ids match file names and every lesson has a quiz`() {
    val errors = buildList {
      parsedLessons().forEach { (file, lesson) ->
        val expected = file.nameWithoutExtension
        if (lesson.id != expected) add("${file.name}: id='${lesson.id}' не совпадает с именем файла")
        if (lesson.title.isBlank()) add("${file.name}: пустой title")
        if (lesson.steps.isEmpty()) add("${file.name}: нет шагов")
        if (lesson.steps.none { it.type == "quiz" && it.questions.isNotEmpty() }) {
          add("${file.name}: нет квиза с вопросами — концепция урока требует закрепление в конце")
        }
        if (lesson.firstRun && expected != "l1_welcome") add("${file.name}: firstRun=true не в l1_welcome")
      }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }

  @Test
  fun `catalogs and lesson files reference each other`() {
    val ids = lessonFiles.map { it.nameWithoutExtension }.toSet()
    val referenced = LIBRARY_LESSON_IDS.toSet() + ROADMAP_LEVELS.map { it.id }
    val missingFiles = referenced - ids
    // l1_welcome не в библиотеке (это онбординг), но в роадмапе — поэтому сироты считаем от общего множества.
    val orphanFiles = ids - referenced
    val errors = buildList {
      missingFiles.forEach { add("'$it' есть в каталоге (Library/Roadmap), но assets/lessons/$it.json отсутствует") }
      orphanFiles.forEach { add("assets/lessons/$it.json не привязан ни к Библиотеке, ни к роадмапу — его никто не увидит") }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }

  @Test
  fun `steps follow the chat controller contract`() {
    val errors = buildList {
      parsedLessons().forEach { (file, lesson) ->
        lesson.steps.forEachIndexed { i, step ->
          fun err(msg: String) = add("${file.name} шаг $i (${step.type}): $msg")
          when (step.type) {
            "text" -> if (step.text.isBlank()) err("пустой text — пустой пузырь в чате")

            "quiz" -> step.questions.forEachIndexed { qi, q ->
              if (q.q.isBlank()) err("вопрос $qi: пустой текст вопроса")
              if (q.options.size < 2) {
                err("вопрос $qi: ${q.options.size} вариантов — не кликается, onAllAnswered не сработает, урок зависнет")
              } else if (q.correct !in q.options.indices) {
                val hint = if (q.correct == q.options.size) " (похоже на 1-based индекс)" else ""
                err("вопрос $qi: correct=${q.correct} вне 0..${q.options.size - 1}$hint")
              }
            }

            "image" -> {
              val asset = step.asset.ifBlank {
                step.imageKey.takeIf { it.isNotBlank() }?.let { "lessons/images/$it.svg" } ?: ""
              }
              when {
                asset.isNotBlank() && !File(assetsDir, asset).isFile -> err("ассет не найден: $asset")
                asset.isBlank() && step.url.isBlank() -> err("нет ни asset/imageKey, ни url — карточка «Нет изображения»")
              }
            }

            "reveal" -> {
              if (step.question.isBlank()) err("пустой question")
              if (step.answer.isBlank()) err("пустой answer — кнопка раскроет пустоту")
            }

            "concept" -> if (step.term.isBlank() || step.definition.isBlank()) err("пустой term/definition")

            "example" -> if (step.parts.isEmpty() || step.parts.all { it.text.isBlank() }) err("пустые parts")

            "fill" -> {
              // FillCardView: a == b && a.isNotEmpty() — пустой answer нерешаем, а кнопки «Дальше» у fill нет.
              if (step.answer.isBlank()) err("пустой answer — шаг нерешаем, урок зависнет")
              if (!step.template.contains("___")) err("в template нет '___' — непонятно, что вписывать")
            }

            "sort" -> {
              if (step.items.size < 2) err("${step.items.size} элементов — нечего сортировать")
              val target = (step.correct as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
              val identity = step.items.indices.toList()
              when {
                target == null || target.size != step.items.size || target.toSet() != identity.toSet() ->
                  err("correct=${step.correct} не перестановка 0..${step.items.size - 1} — рендер деградирует к исходному порядку")
                target == identity ->
                  err("correct — identity: items уже стоят правильно, шаг решается тапом «Проверить» без действий")
              }
            }

            "match" -> {
              if (step.pairs.size < 2) err("${step.pairs.size} пар — сопоставлять нечего")
              step.pairs.forEachIndexed { pi, p ->
                if (p.term.isBlank() || p.def.isBlank()) err("пара $pi: пустой term/def")
              }
            }

            "scenario" -> {
              // ScenarioCardView без вариантов не кликается, а кнопки «Дальше» у scenario нет.
              if (step.options.isEmpty()) err("нет options — нечего тапнуть, урок зависнет")
              val correct = (step.correct as? JsonPrimitive)?.intOrNull
              if (correct == null || correct !in step.options.indices) {
                err("correct=${step.correct} вне 0..${step.options.size - 1}")
              }
            }

            "input" -> if (step.prompt.isBlank()) err("пустой prompt — пустой пузырь")

            "challenge" -> if (step.instruction.isBlank() && step.task.isBlank()) err("пустые instruction/task — пустой пузырь")

            // Setup-шаги онбординга: чат-контроллер их не умеет (рендерит пустой текст без кнопки).
            "language", "persona", "profile" ->
              if (lesson.id != "l1_welcome") err("setup-тип допустим только в l1_welcome — в чат-уроке он зависнет")

            else -> err("неизвестный тип — пустой пузырь и НЕТ кнопки «Дальше», урок зависнет")
          }
        }
      }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }
}
