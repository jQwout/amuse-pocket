package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2
// @trace user-agent-builder-848153
// @trace kartinki-urokov-cherez-muse-image-fd6f8c

import android.net.Uri
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Драйвит шаги JSON-урока как СТРОГИЙ контракт прямо в чате: код решает последовательность шагов и
 * что рисовать, публикуя по одному сообщению ассистента на шаг. LLM в этот поток не вмешивается —
 * чётко разделяя «шаги урока» (код) и «команды для LLM-чата» (явные хода, которых тут нет).
 *
 * Контракт: 1 шаг = ровно 1 assistant-сообщение. Поэтому позицию в уроке можно восстановить простым
 * счётом assistant-сообщений (этим пользуется [com.aiopenpocket.ui.chat.ChatViewModel]).
 */
@Inject
@SingleIn(AppScope::class)
class LessonChatController(
  private val conversationRepository: ConversationRepository,
  private val imageGenerator: LessonImageGenerator,
) {

  /**
   * Заранее рисует картинки шагов урока, начиная с [fromIndex], — по порядку шагов, чтобы ближайшая
   * была готова первой. Muse Image думает перед отрисовкой десятки секунд; без предзагрузки это
   * время пользователь простоял бы на кнопке «Дальше». Уже нарисованные берутся из кеша мгновенно.
   */
  suspend fun prefetchImages(lesson: Lesson, fromIndex: Int = 0) {
    lesson.steps.drop(fromIndex)
      .filter { it.needsGeneratedImage() }
      .forEach { imageGenerator.imageFor(it.imagePrompt) }
  }

  /**
   * Публикует шаг урока [index] как одно сообщение ассистента. text → текст; остальные типы с
   * визуалом/интерактивом → driven-UI карточка (по одному cardSpec на шаг).
   */
  suspend fun postStep(conversationId: String, lesson: Lesson, index: Int) {
    val step = lesson.steps.getOrNull(index) ?: return
    val cardSpec = when (step.type) {
      "quiz" -> buildQuizCardSpec(step)
      "image" -> buildImageCardSpec(step)
      "reveal" -> buildRevealCardSpec(step)
      "concept" -> buildConceptCardSpec(step)
      "example" -> buildExampleCardSpec(step)
      "fill" -> buildFillCardSpec(step)
      "sort" -> buildSortCardSpec(step)
      "match" -> buildMatchCardSpec(step)
      "scenario" -> buildScenarioCardSpec(step)
      else -> null
    }
    if (cardSpec != null) {
      conversationRepository.addMessage(conversationId, "assistant", "", cardSpec = cardSpec)
    } else {
      conversationRepository.addMessage(conversationId, "assistant", textForStep(step))
    }
  }

  /**
   * Текст для шагов без карточки. input/challenge (живой чат) пока не реализованы — показываем их
   * авторский текст как обычный шаг, чтобы урок не выдавал пустой пузырь. Это временно (фаза 2).
   */
  private fun textForStep(step: LessonStep): String = when (step.type) {
    "input" -> listOf(step.prompt, step.hint).filter { it.isNotBlank() }.joinToString("\n\n")
    "challenge" -> listOf(step.instruction, step.task).filter { it.isNotBlank() }.joinToString("\n\n")
    else -> step.text
  }

  /**
   * Финальная карточка «урок пройден»: её кнопка отметит уровень пройденным и вернёт на список.
   *
   * Капстоун Академии (Task 23): если ПОСЛЕДНИЙ шаг урока несёт [LessonStep.primaryAction] (сегодня
   * только `agent_blueprint` его задаёт — см. её json), прокидываем CTA в params карточки как
   * `primary_action` (снейк-кейс — та же трансляция camelCase-ассета в snake_case card-params, что и
   * в [buildFillCardSpec] для `case_sensitive`). У обычных уроков последний шаг primaryAction не
   * задаёт → params не меняются, и completion-поведение остаётся прежним byte-for-byte.
   */
  suspend fun postCompletion(conversationId: String, lesson: Lesson) {
    val params = JSONObject()
      .put("title", "Урок «${lesson.title}» пройден!")
      .put("subtitle", "Отличная работа.")
      .put("button_label", "Готово")
    lesson.steps.lastOrNull()?.primaryAction?.let { action ->
      params.put(
        "primary_action",
        JSONObject().put("type", action.type).put("label", action.label),
      )
    }
    val spec = JSONObject().put("template", "lesson_complete").put("params", params).toString()
    conversationRepository.addMessage(conversationId, "assistant", "", cardSpec = spec)
  }

  /** Собирает cardSpec для интерактивной карточки `lesson_quiz` из quiz-шага урока. */
  private fun buildQuizCardSpec(step: LessonStep): String {
    val questions = JSONArray()
    step.questions.forEach { q ->
      questions.put(
        JSONObject()
          .put("q", q.q)
          .put("options", JSONArray(q.options))
          .put("correct", q.correct)
          .put("explain", q.explain),
      )
    }
    val params = JSONObject().put("questions", questions)
    return JSONObject().put("template", "lesson_quiz").put("params", params).toString()
  }

  /**
   * Собирает cardSpec для карточки `image`. Источники по порядку: ассет из бандла (работает без
   * сети), картинка Muse Image по [LessonStep.imagePrompt], сетевой [LessonStep.url].
   *
   * Сгенерированная картинка уходит в `url` карточки как `file://` — рендер её от сетевой не
   * отличает. Ждём её не дольше [STEP_IMAGE_WAIT_MS]: обычно она уже в кеше после
   * [prefetchImages], а если нет — шаг выходит без неё. Держать пользователя на «Дальше» дольше
   * хуже, чем показать шаг без картинки.
   */
  private suspend fun buildImageCardSpec(step: LessonStep): String {
    val asset = step.bundledImageAsset()
    val generated = if (step.needsGeneratedImage()) {
      withTimeoutOrNull(STEP_IMAGE_WAIT_MS) { imageGenerator.imageFor(step.imagePrompt) }
    } else {
      null
    }
    val params = JSONObject()
      .put("asset", asset)
      .put("url", generated?.let { Uri.fromFile(it).toString() } ?: step.url)
      .put("caption", step.caption.ifBlank { step.alt })
    return JSONObject().put("template", "image").put("params", params).toString()
  }

  // imageKey — короткая форма: lessons/images/{key}.svg. Явный asset имеет приоритет.
  private fun LessonStep.bundledImageAsset(): String =
    asset.ifBlank { imageKey.takeIf { it.isNotBlank() }?.let { "lessons/images/$it.svg" } ?: "" }

  /** Рисовать есть смысл только шагу-картинке с промптом и без своего ассета. */
  private fun LessonStep.needsGeneratedImage(): Boolean =
    type == "image" && imagePrompt.isNotBlank() && bundledImageAsset().isBlank()

  /** "reveal": вопрос, по тапу раскрывается ответ. */
  private fun buildRevealCardSpec(step: LessonStep): String {
    val params = JSONObject().put("question", step.question).put("answer", step.answer)
    return JSONObject().put("template", "reveal").put("params", params).toString()
  }

  /** "concept": термин + определение + пример. */
  private fun buildConceptCardSpec(step: LessonStep): String {
    val params = JSONObject()
      .put("term", step.term)
      .put("definition", step.definition)
      .put("example", step.example)
    return JSONObject().put("template", "concept").put("params", params).toString()
  }

  /** "example": заголовок + разбор на части (текст/роль/заметка). */
  private fun buildExampleCardSpec(step: LessonStep): String {
    val parts = JSONArray()
    step.parts.forEach { p ->
      parts.put(JSONObject().put("text", p.text).put("role", p.role).put("note", p.note))
    }
    val params = JSONObject().put("label", step.label).put("parts", parts)
    return JSONObject().put("template", "example").put("params", params).toString()
  }

  /** "fill": предложение с пропуском `___`, ответ проверяется по [LessonStep.answer]. */
  private fun buildFillCardSpec(step: LessonStep): String {
    val params = JSONObject()
      .put("template", step.template)
      .put("answer", step.answer)
      .put("case_sensitive", step.caseSensitive)
    return JSONObject().put("template", "fill").put("params", params).toString()
  }

  /** "sort": расставить items в правильный порядок (correct — массив целевых индексов). */
  private fun buildSortCardSpec(step: LessonStep): String {
    val correct = JSONArray()
    (step.correct as? JsonArray)?.forEach { el ->
      (el as? JsonPrimitive)?.intOrNull?.let { correct.put(it) }
    }
    val params = JSONObject()
      .put("instruction", step.instruction)
      .put("items", JSONArray(step.items))
      .put("correct", correct)
      .put("explain", step.explain)
    return JSONObject().put("template", "sort").put("params", params).toString()
  }

  /** "match": сопоставить термины и определения. */
  private fun buildMatchCardSpec(step: LessonStep): String {
    val pairs = JSONArray()
    step.pairs.forEach { p ->
      pairs.put(JSONObject().put("term", p.term).put("def", p.def))
    }
    val params = JSONObject().put("instruction", step.instruction).put("pairs", pairs)
    return JSONObject().put("template", "match").put("params", params).toString()
  }

  /** "scenario": ситуативный вопрос с одним правильным вариантом (correct — целое). */
  private fun buildScenarioCardSpec(step: LessonStep): String {
    // -1, а не 0: при отсутствующем/битом correct не помечаем первый вариант как «правильный».
    val correctIndex = step.scenarioCorrectIndex()
    val params = JSONObject()
      .put("situation", step.situation)
      .put("q", step.q)
      .put("options", JSONArray(step.options))
      .put("correct", correctIndex)
      .put("explain", step.explain)
    return JSONObject().put("template", "scenario").put("params", params).toString()
  }

  private companion object {
    const val STEP_IMAGE_WAIT_MS = 20_000L
  }
}
