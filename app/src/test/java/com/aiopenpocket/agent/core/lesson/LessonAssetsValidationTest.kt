package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2
// @trace english-ui-on-the-demo-path-223241

import com.aiopenpocket.agent.core.llm.SegmentationConcept
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.roadmap.LIBRARY_LESSON_IDS
import com.aiopenpocket.ui.roadmap.ROADMAP_LEVELS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A contract lint of the lessons in assets/lessons. A broken lesson does NOT crash at runtime, it silently
 * disappears (LessonRepository.getLessonOrNull swallows the parse error: the lesson vanishes from the
 * Library, the roadmap level degrades into a legacy chat), and a "stuck" step leaves the user without the
 * "Next" button forever. So we fail the build here.
 *
 * The rules are derived from the code and must change together with it:
 *  - step types and card rendering — LessonChatController.postStep / DrivenUiEngine;
 *  - which steps advance by the "Next" button — ChatScreen (needsManualAdvance);
 *  - parsing strictness — LessonRepository (Json { ignoreUnknownKeys = true }).
 */
class LessonAssetsValidationTest {

  // The same config as in LessonRepository — otherwise the lint would check something other than what the app sees.
  private val json = Json { ignoreUnknownKeys = true }

  // Gradle unit tests run from the module directory (app/); the fallback path is the project root.
  private val assetsDir: File = sequenceOf(File("src/main/assets"), File("app/src/main/assets"))
    .firstOrNull { it.isDirectory }
    ?: error("Не нашёл src/main/assets; рабочая директория: ${File(".").absolutePath}")

  private val lessonsDir = File(assetsDir, "lessons")

  /** Russian lessons are the originals: every lesson exists in Russian, and the catalogs refer to them. */
  private val lessonFiles: List<File> = lessonsDir
    .listFiles { f -> f.extension == "json" }
    ?.sortedBy { it.name }
    .orEmpty()

  /**
   * The English copies (assets/lessons/en, LessonRepository reads them first with the English interface).
   * They are linted by the same rules as the Russian ones and also checked against the original.
   */
  private val enLessonFiles: List<File> = File(lessonsDir, AppLanguage.EN)
    .listFiles { f -> f.extension == "json" }
    ?.sortedBy { it.name }
    .orEmpty()

  private val allLessonFiles: List<File> = lessonFiles + enLessonFiles

  /** The file name in messages: `meta_models.json` or `en/meta_models.json` — so copies are not confused with originals. */
  private fun label(file: File): String = file.relativeTo(lessonsDir).invariantSeparatorsPath

  /** The lessons that parsed; parse errors are checked by a separate test. */
  private fun parsedLessons(files: List<File> = allLessonFiles): List<Pair<File, Lesson>> = files.mapNotNull { file ->
    runCatching { file to json.decodeFromString(Lesson.serializer(), file.readText()) }.getOrNull()
  }

  @Test
  fun `lesson assets exist`() {
    assertTrue("В ${File(assetsDir, "lessons").absolutePath} нет ни одного урока", lessonFiles.isNotEmpty())
  }

  @Test
  fun `every lesson parses with the runtime serializer`() {
    val errors = allLessonFiles.mapNotNull { file ->
      runCatching { json.decodeFromString(Lesson.serializer(), file.readText()) }
        .exceptionOrNull()
        ?.let { "${label(file)}: ${it.message?.lineSequence()?.firstOrNull()}" }
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
        val name = label(file)
        val expected = file.nameWithoutExtension
        if (lesson.id != expected) add("$name: id='${lesson.id}' не совпадает с именем файла")
        if (lesson.title.isBlank()) add("$name: пустой title")
        if (lesson.steps.isEmpty()) add("$name: нет шагов")
        if (lesson.steps.none { it.type == "quiz" && it.questions.isNotEmpty() }) {
          add("$name: нет квиза с вопросами — концепция урока требует закрепление в конце")
        }
        if (lesson.firstRun && expected != "l1_welcome") add("$name: firstRun=true не в l1_welcome")
      }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }

  @Test
  fun `catalogs and lesson files reference each other`() {
    val ids = lessonFiles.map { it.nameWithoutExtension }.toSet()
    val referenced = LIBRARY_LESSON_IDS.toSet() + ROADMAP_LEVELS.map { it.id }
    val missingFiles = referenced - ids
    // l1_welcome is not in the library (it is onboarding) but is in the roadmap — so orphans are counted from the union.
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
          fun err(msg: String) = add("${label(file)} шаг $i (${step.type}): $msg")
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
                asset.isBlank() && step.url.isBlank() && step.imagePrompt.isBlank() ->
                  err("нет ни asset/imageKey, ни url, ни imagePrompt — карточка «Нет изображения»")
              }
              // The pregeneration script (tools/lesson-images) looks for the prompt in the file verbatim and writes
              // asset in front of it: a quote or a backslash would change the JSON entry. And in English the
              // model draws more precisely — hence printable ASCII.
              if (step.imagePrompt.isNotBlank() && !step.imagePrompt.all { it in ' '..'~' && it != '"' && it != '\\' }) {
                err("imagePrompt: только печатный ASCII без кавычек и обратных слешей")
              }
            }

            "animation" -> if (step.scene !in LESSON_ANIMATION_SCENES) {
              err("сцена «${step.scene}» не из $LESSON_ANIMATION_SCENES — вместо анимации будет заглушка")
            }

            "video" -> {
              if (step.instruction.isBlank()) err("пустой instruction — непонятно, что снимать")
              if (step.videoPrompt.isBlank()) err("пустой videoPrompt — модели нечего делать с роликом")
              // A "Show an example" button with a broken path would turn the step's main path into an error.
              for (sample in listOf(step.sampleVideo, step.samplePreview)) {
                if (sample.isNotBlank() && !File(assetsDir, sample).isFile) err("ролик-пример не найден: $sample")
              }
              if (step.sampleVideo.isNotBlank() && File(assetsDir, step.sampleVideo).length() > VideoUnderstandingClient.MAX_VIDEO_BYTES) {
                err("ролик-пример больше потолка VideoUnderstandingClient.MAX_VIDEO_BYTES")
              }
            }

            "segment" -> {
              if (step.instruction.isBlank()) err("пустой instruction — непонятно, что делать")
              // The default prompt goes to SAM as is when the learner does not edit it, so it must pass the very
              // check the client applies: one noun phrase in English (Latin letters, so a Russian word fails too),
              // no list and no command. A prompt the client rejects would make the step's main button a
              // guaranteed error — in the Russian lesson as well: SAM is asked in English.
              SegmentationConcept.problemOf(step.segmentPrompt)?.let {
                err("segmentPrompt «${step.segmentPrompt}» не годится как понятие для SAM: $it")
              }
              // The "Show an example" button with a broken path would turn the step's main path into an error.
              if (step.sampleImage.isNotBlank() && !File(assetsDir, step.sampleImage).isFile) {
                err("фото-пример не найдено: ${step.sampleImage}")
              }
            }

            "reveal" -> {
              if (step.question.isBlank()) err("пустой question")
              if (step.answer.isBlank()) err("пустой answer — кнопка раскроет пустоту")
            }

            "concept" -> if (step.term.isBlank() || step.definition.isBlank()) err("пустой term/definition")

            "example" -> if (step.parts.isEmpty() || step.parts.all { it.text.isBlank() }) err("пустые parts")

            "fill" -> {
              // FillCard.accepts: empty input is never accepted — an empty answer is unsolvable without a hint, and
              // after two misses the card would show an empty "Correct: «»".
              if (step.answer.isBlank()) err("пустой answer — шаг нерешаем, урок зависнет")
              if (!step.template.contains("___")) err("в template нет '___' — непонятно, что вписывать")
              // The renderer silently drops an empty string in answers — so the author thought the variant counts
              // when it does not. Better to fail here.
              step.answers.forEachIndexed { ai, alt ->
                if (alt.isBlank()) err("answers[$ai]: пустая строка — такой вариант не засчитается")
              }
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
              // ScenarioCardView without options cannot be clicked, and scenario has no "Next" button.
              if (step.options.isEmpty()) err("нет options — нечего тапнуть, урок зависнет")
              // scenarioCorrectIndex() = the same decoding as the renderer's: -1 for a missing/broken key.
              if (step.scenarioCorrectIndex() !in step.options.indices) {
                err("correct=${step.correct} вне 0..${step.options.size - 1}")
              }
            }

            // input/challenge (live input) are not supported while lesson mode hides the input field:
            // they render as static text and promise interaction that is not there.
            "input", "challenge" ->
              err("тип пока не поддержан: lesson-mode скрывает ввод; используй text с честной практикой")

            // Onboarding setup steps: the chat controller cannot handle them (it renders empty text without a button).
            "language", "persona", "profile" ->
              if (lesson.id != "l1_welcome") err("setup-тип допустим только в l1_welcome — в чат-уроке он зависнет")

            else -> err("неизвестный тип — пустой пузырь и НЕТ кнопки «Дальше», урок зависнет")
          }
        }
      }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }

  /**
   * All the "pick an option" cards the renderer shuffles: quiz questions and scenario steps of lessons plus
   * the hardcoded roadmap/onboarding quizzes. One traversal — so a new step type with options cannot be
   * added past the option-level lints below (they would silently skip it otherwise).
   * Each element: (place, options, 0-based index of the right one).
   */
  private fun optionSets(): List<Triple<String, List<String>, Int>> = buildList {
    parsedLessons().forEach { (file, lesson) ->
      lesson.steps.forEachIndexed { i, step ->
        when (step.type) {
          "quiz" -> step.questions.forEachIndexed { qi, q ->
            add(Triple("${label(file)} шаг $i вопрос $qi", q.options, q.correct))
          }
          "scenario" -> add(Triple("${label(file)} шаг $i (scenario)", step.options, step.scenarioCorrectIndex()))
        }
      }
    }
  }

  /**
   * The renderer shuffles the options (stableShuffledIndices), but it cannot hide length: in the authored
   * content the right answer was several times longer than the distractors, and the quiz was solved by the
   * rule "press the longest button" without reading. The threshold is soft: distractors may be longer than
   * the right answer, only a clear skew in its favor is banned.
   */
  private fun lengthGiveaway(options: List<String>, correct: Int): String? {
    if (options.size < 2 || correct !in options.indices) return null
    val correctLen = options[correct].length
    val maxDistractor = options.filterIndexed { i, _ -> i != correct }.maxOf { it.length }
    return if (correctLen > maxDistractor * 1.5 && correctLen - maxDistractor > 12) {
      "правильный вариант ($correctLen зн.) заметно длиннее всех дистракторов (макс. $maxDistractor зн.) — длина выдаёт ответ"
    } else {
      null
    }
  }

  @Test
  fun `correct option must not be the giveaway-longest`() {
    val errors = optionSets().mapNotNull { (location, options, correct) ->
      lengthGiveaway(options, correct)?.let { "$location: $it" }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }

  /**
   * A lesson whose steps send the learner's PHOTO to a provider has to say so in its privacy paragraph. In how_model_sees
   * the paragraph spoke of the video only, and it sits right after the step that sends a photo to Meta.
   */
  @Test
  fun `a lesson with a photo step says in its privacy paragraph that photos leave the phone`() {
    val errors = buildList {
      parsedLessons().forEach { (file, lesson) ->
        val segmentAt = lesson.steps.indexOfFirst { it.type == "segment" }
        if (segmentAt < 0) return@forEach
        val privacy = lesson.steps.drop(segmentAt + 1)
          .filter { it.type == "text" }
          .firstOrNull { it.text.contains("приватност", ignoreCase = true) || it.text.contains("privacy", ignoreCase = true) }
        when {
          privacy == null -> add("${label(file)}: после шага segment нет абзаца о приватности")
          !(privacy.text.contains("фото", ignoreCase = true) || privacy.text.contains("photo", ignoreCase = true)) ->
            add("${label(file)}: абзац о приватности не упоминает фото, а шаг segment отправляет фото в Meta")
        }
      }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }

  // --- Task 23: agent_blueprint (the AI Academy capstone) bridges into the agent builder ---

  /**
   * `LessonChatController.postCompletion` reads `lesson.steps.lastOrNull()?.primaryAction` to decide
   * whether the completion card gets the "Open the builder" CTA (see DrivenUiEngineTest for the
   * card-parsing side). Here we lint the SOURCE of that data: the capstone asset itself.
   */
  @Test
  fun `capstone lesson's final step carries a valid primary_action`() {
    // All language copies of the capstone: an English one without the CTA would lose the bridge to the builder.
    val capstones = parsedLessons().filter { (f, _) -> f.nameWithoutExtension == "agent_blueprint" }
    assertTrue("agent_blueprint.json не найден среди распарсенных уроков", capstones.isNotEmpty())
    capstones.forEach { (file, lesson) ->
      val action = lesson.steps.lastOrNull()?.primaryAction
      assertTrue("${label(file)}: финальный шаг капстоуна должен нести primaryAction", action != null)
      assertEquals("${label(file)}: primaryAction.type", "open_agent_builder", action?.type)
      assertTrue("${label(file)}: primaryAction.label пуст", action?.label?.isNotBlank() == true)
    }
  }

  /**
   * Defense in depth mirroring DrivenUiEngine's own runtime rejection of an unknown `primary_action`
   * type: a typo'd type here would otherwise ship silently and only surface as a dead/erroring card
   * at runtime. Also enforces the "capstone-only" rule at the asset level — no OTHER lesson, and no
   * OTHER step of the capstone itself, may author this field.
   */
  @Test
  fun `primary_action is authored only on the capstone's final step, with a known type`() {
    val errors = buildList {
      parsedLessons().forEach { (file, lesson) ->
        lesson.steps.forEachIndexed { i, step ->
          val action = step.primaryAction ?: return@forEachIndexed
          if (action.type != "open_agent_builder") {
            add("${label(file)} шаг $i: неизвестный primaryAction.type='${action.type}' — DrivenUiEngine отвергнет карточку целиком")
          }
          if (lesson.id != "agent_blueprint" || i != lesson.steps.lastIndex) {
            add("${label(file)} шаг $i: primaryAction задан вне финального шага капстоуна — CTA должен остаться только там")
          }
        }
      }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }

  // --- English copies of the lessons (assets/lessons/en) ---

  /**
   * Translation changes only the text. The copy's steps, their types, scenes, assets and answer keys are the
   * same as the Russian original's: otherwise the English lesson would quietly drift from the Russian one
   * (a different right option, a missing image, an extra step), and the lesson cursor — the step number —
   * would stop meaning the same thing when the language changes mid-lesson. imagePrompt is shared too: it
   * is the key for finding an already drawn image. videoPrompt may be translated — it goes to the model,
   * not into the markup.
   */
  @Test
  fun `english lessons mirror their russian originals`() {
    val originals = parsedLessons(lessonFiles).associate { (f, lesson) -> f.nameWithoutExtension to lesson }
    val errors = buildList {
      parsedLessons(enLessonFiles).forEach { (file, en) ->
        val name = label(file)
        val ru = originals[file.nameWithoutExtension]
        if (ru == null) {
          add("$name: нет русского оригинала assets/lessons/${file.name} — копию никто не откроет")
          return@forEach
        }
        if (en.iconKey != ru.iconKey) add("$name: iconKey='${en.iconKey}', в оригинале '${ru.iconKey}'")
        if (en.firstRun != ru.firstRun) add("$name: firstRun=${en.firstRun}, в оригинале ${ru.firstRun}")
        if (en.steps.size != ru.steps.size) {
          add("$name: шагов ${en.steps.size}, в оригинале ${ru.steps.size}")
          return@forEach
        }
        en.steps.zip(ru.steps).forEachIndexed { i, (e, r) ->
          fun err(msg: String) = add("$name шаг $i (${r.type}): $msg")
          if (e.type != r.type) {
            err("тип '${e.type}', в оригинале '${r.type}'")
            return@forEachIndexed
          }
          val shared = listOf(
            Triple("scene", e.scene, r.scene),
            Triple("asset", e.asset, r.asset),
            Triple("imageKey", e.imageKey, r.imageKey),
            Triple("url", e.url, r.url),
            Triple("imagePrompt", e.imagePrompt, r.imagePrompt),
            Triple("sampleVideo", e.sampleVideo, r.sampleVideo),
            Triple("samplePreview", e.samplePreview, r.samplePreview),
            // The SAM prompt is English in both languages and goes to the model verbatim.
            Triple("segmentPrompt", e.segmentPrompt, r.segmentPrompt),
            Triple("sampleImage", e.sampleImage, r.sampleImage),
          )
          shared.forEach { (field, mine, original) ->
            if (mine != original) err("$field='$mine', в оригинале '$original'")
          }
          if (e.caseSensitive != r.caseSensitive) err("caseSensitive=${e.caseSensitive}, в оригинале ${r.caseSensitive}")
          if (e.primaryAction?.type != r.primaryAction?.type) {
            err("primaryAction.type='${e.primaryAction?.type}', в оригинале '${r.primaryAction?.type}'")
          }
          when (e.type) {
            "quiz" -> {
              if (e.questions.size != r.questions.size) err("вопросов ${e.questions.size}, в оригинале ${r.questions.size}")
              e.questions.zip(r.questions).forEachIndexed { qi, (eq, rq) ->
                if (eq.options.size != rq.options.size) err("вопрос $qi: вариантов ${eq.options.size}, в оригинале ${rq.options.size}")
                if (eq.correct != rq.correct) err("вопрос $qi: correct=${eq.correct}, в оригинале ${rq.correct}")
              }
            }
            "scenario" -> {
              if (e.options.size != r.options.size) err("вариантов ${e.options.size}, в оригинале ${r.options.size}")
              if (e.scenarioCorrectIndex() != r.scenarioCorrectIndex()) {
                err("correct=${e.scenarioCorrectIndex()}, в оригинале ${r.scenarioCorrectIndex()}")
              }
            }
            "sort" -> {
              if (e.items.size != r.items.size) err("элементов ${e.items.size}, в оригинале ${r.items.size}")
              if (e.correct != r.correct) err("correct=${e.correct}, в оригинале ${r.correct}")
            }
            "match" -> if (e.pairs.size != r.pairs.size) err("пар ${e.pairs.size}, в оригинале ${r.pairs.size}")
            "example" -> if (e.parts.size != r.parts.size) err("частей ${e.parts.size}, в оригинале ${r.parts.size}")
          }
        }
      }
    }
    assertTrue(errors.joinToString("\n"), errors.isEmpty())
  }
}
