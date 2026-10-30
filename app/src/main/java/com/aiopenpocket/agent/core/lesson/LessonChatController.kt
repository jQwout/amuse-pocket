package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2
// @trace user-agent-builder-848153
// @trace kartinki-urokov-cherez-muse-image-fd6f8c
// @trace video-na-vhod-v-uroke-muse-spark-08ccdb
// @trace protsedurnye-animatsii-v-urokah-68b2bf
// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import android.net.Uri
import com.aiopenpocket.agent.core.llm.SegmentationClient
import com.aiopenpocket.agent.core.llm.SegmentationFailure
import com.aiopenpocket.agent.core.llm.SegmentationResult
import com.aiopenpocket.agent.core.llm.VideoUnderstandingClient
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Drives the steps of a JSON lesson as a STRICT contract right in the chat: code decides the order of
 * steps and what to render, publishing one assistant message per step. The LLM does not interfere with
 * this flow — a clear split between "lesson steps" (code) and "commands for the LLM chat" (explicit
 * turns, of which there are none here).
 *
 * Contract: 1 step = exactly 1 assistant message. The lesson cursor is explicit conversation state
 * (lessonStepIndex in [com.aiopenpocket.ui.chat.ChatViewModel]), not a message count: so extra
 * messages — an answer to a question asked during the lesson, a "Draw it for me" image — do not move
 * the step.
 */
@Inject
@SingleIn(AppScope::class)
class LessonChatController(
  private val conversationRepository: ConversationRepository,
  private val imageGenerator: LessonImageGenerator,
  private val videoClient: VideoUnderstandingClient,
  private val segmentationClient: SegmentationClient,
) {

  /**
   * Muse Spark's answer to the user's clip for a "video" step: the lesson's task ([videoPrompt]) plus
   * the answer language. The answer lands in the chat as a normal assistant message under the step's
   * card; the lesson cursor does not move. `false` — it could not be analyzed, there is no message.
   */
  suspend fun postVideoAnswer(
    conversationId: String,
    videoPrompt: String,
    video: ByteArray,
    mimeType: String,
    languageCode: String,
  ): Boolean {
    val language = java.util.Locale.forLanguageTag(languageCode)
      .getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { "the learner's language" }
    val instruction = "${videoPrompt.trim()}\n\nAnswer in $language."
    val answer = videoClient.describe(instruction, video, mimeType) ?: return false
    conversationRepository.addMessage(conversationId, "assistant", answer)
    return true
  }

  /**
   * Whether "Draw it for me" can run at all: Muse Image needs a Meta Model API key. Asked first so the
   * learner without one is told to add it, rather than that drawing is "unavailable".
   */
  fun drawingReady(): Boolean = imageGenerator.isConfigured()

  /** Whether the "video" step can run at all: Muse Spark needs a Meta Model API key. Asked before the clip is read. */
  fun videoReady(): Boolean = videoClient.isConfigured()

  /**
   * Whether the "segment" step can run at all: SAM needs a Meta Model API key. Asked BEFORE the photo is
   * prepared, so a learner without a key gets the explanation at once instead of after a pointless decode.
   */
  fun segmentationReady(): Boolean = segmentationClient.isConfigured()

  /**
   * SAM 3.1 outlines [concept] in [photo]; the boxes land in the chat as a result card under the step.
   * The lesson cursor does not move, as with [postVideoAnswer].
   *
   * Returns `null` when the card was posted, otherwise why it was not (no key, no billing, no network…).
   * Zero matches is NOT a failure: the card is posted and says that nothing was found — an empty answer
   * is a normal answer of this model, and "try another word" is part of what the step teaches.
   */
  suspend fun postSegmentation(conversationId: String, concept: String, photo: PreparedPhoto): SegmentationFailure? {
    val result = segmentationClient.segment(concept, photo.bytes, photo.mimeType)
    if (result is SegmentationResult.Failed) return result.reason
    val found = result as SegmentationResult.Success
    conversationRepository.addMessage(
      conversationId,
      "assistant",
      "",
      cardSpec = buildSegmentResultCardSpec(found, photo),
    )
    return null
  }

  /**
   * Draws the images of the lesson steps in advance, starting at [fromIndex] — in step order, so the
   * nearest one is ready first. Muse Image thinks for tens of seconds before drawing; without the
   * prefetch the user would spend that time standing on the "Next" button. Already drawn ones come from
   * the cache instantly.
   */
  suspend fun prefetchImages(lesson: Lesson, fromIndex: Int = 0) {
    lesson.steps.drop(fromIndex)
      .filter { it.needsGeneratedImage() }
      .forEach { imageGenerator.imageFor(it.imagePrompt) }
  }

  /**
   * "Draw it for me": the step's image again, tailored to the user's "about me" and language
   * ([personalizedImagePrompt]). Published as a separate message under the step — "before / after" is
   * visible.
   *
   * No [STEP_IMAGE_WAIT_MS]: the user tapped it themselves and sees "Drawing…", so they are willing to
   * wait; there is still a ceiling — the request timeout in ImageGenClient. The new card has no prompt,
   * hence no button: there is no point redrawing a redrawn image. `false` — drawing failed, there is no
   * message.
   */
  suspend fun postPersonalizedImage(
    conversationId: String,
    imagePrompt: String,
    caption: String,
    aboutMe: String,
    languageCode: String,
  ): Boolean {
    val file = imageGenerator.imageFor(personalizedImagePrompt(imagePrompt, aboutMe, languageCode)) ?: return false
    val params = JSONObject()
      .put("asset", "")
      .put("url", Uri.fromFile(file).toString())
      // The caption is baked into the message in the language at the moment of publishing — like the text of the lesson steps.
      .put(
        "caption",
        if (caption.isBlank()) {
          tr("Нарисовано под тебя", "Drawn for you")
        } else {
          tr("$caption — нарисовано под тебя", "$caption — drawn for you")
        },
      )
    val spec = JSONObject().put("template", "image").put("params", params).toString()
    conversationRepository.addMessage(conversationId, "assistant", "", cardSpec = spec)
    return true
  }

  /**
   * Publishes lesson step [index] as one assistant message. text → text; the other types with visuals/
   * interaction → a driven-UI card (one cardSpec per step).
   */
  suspend fun postStep(conversationId: String, lesson: Lesson, index: Int) {
    val step = lesson.steps.getOrNull(index) ?: return
    val cardSpec = when (step.type) {
      "quiz" -> buildQuizCardSpec(step)
      "image" -> buildImageCardSpec(step)
      "video" -> buildVideoCardSpec(step)
      "segment" -> buildSegmentCardSpec(step)
      "animation" -> buildAnimationCardSpec(step)
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
   * Text for steps without a card. input/challenge (a live chat) are not implemented yet — their
   * authored text is shown as a normal step so the lesson does not produce an empty bubble. This is
   * temporary (phase 2).
   */
  private fun textForStep(step: LessonStep): String = when (step.type) {
    "input" -> listOf(step.prompt, step.hint).filter { it.isNotBlank() }.joinToString("\n\n")
    "challenge" -> listOf(step.instruction, step.task).filter { it.isNotBlank() }.joinToString("\n\n")
    else -> step.text
  }

  /**
   * The final "lesson complete" card: its button marks the level as done and returns to the list.
   *
   * The Academy capstone (Task 23): if the LAST step of the lesson carries [LessonStep.primaryAction]
   * (today only `agent_blueprint` sets it — see its json), the CTA is passed into the card params as
   * `primary_action` (snake case — the same camelCase-asset to snake_case card-params translation as in
   * [buildFillCardSpec] for `case_sensitive`). For ordinary lessons the last step sets no primaryAction →
   * the params do not change, and the completion behavior stays byte-for-byte the same.
   */
  suspend fun postCompletion(conversationId: String, lesson: Lesson) {
    // The card's texts are baked into the message in the language at the moment of publishing (like the
    // lesson steps): lesson.title is already in that language — LessonRepository returns the English
    // copy of the lesson if there is one.
    val params = JSONObject()
      .put("title", tr("Урок «${lesson.title}» пройден!", "Lesson “${lesson.title}” complete!"))
      .put("subtitle", tr("Отличная работа.", "Great job."))
      .put("button_label", tr("Готово", "Done"))
    lesson.steps.lastOrNull()?.primaryAction?.let { action ->
      params.put(
        "primary_action",
        JSONObject().put("type", action.type).put("label", action.label),
      )
    }
    val spec = JSONObject().put("template", "lesson_complete").put("params", params).toString()
    conversationRepository.addMessage(conversationId, "assistant", "", cardSpec = spec)
  }

  /** Builds the cardSpec for the interactive `lesson_quiz` card from the lesson's quiz step. */
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
   * Builds the cardSpec for the `image` card. Sources in order: a bundled asset (works without the
   * network), a Muse Image picture from [LessonStep.imagePrompt], a network [LessonStep.url].
   *
   * A generated image goes into the card's `url` as `file://` — the renderer does not tell it from a
   * network one. We wait for it no longer than [STEP_IMAGE_WAIT_MS]: usually it is already in the cache
   * after [prefetchImages], and if not, the step comes out without it. Keeping the user on "Next" longer
   * is worse than showing the step without the image.
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
    // The prompt rides in the card for the sake of the "Draw it for me" button: you can only redraw for
    // the user what you know how to draw.
    if (step.imagePrompt.isNotBlank()) params.put("image_prompt", step.imagePrompt)
    return JSONObject().put("template", "image").put("params", params).toString()
  }

  /** "animation": a procedural scene by id + a caption under it. */
  private fun buildAnimationCardSpec(step: LessonStep): String {
    val params = JSONObject().put("scene", step.scene).put("caption", step.caption)
    return JSONObject().put("template", "lesson_animation").put("params", params).toString()
  }

  /** "video": what to film (the user sees it) + the task for the model (goes to Muse Spark with the clip). */
  private fun buildVideoCardSpec(step: LessonStep): String {
    val params = JSONObject()
      .put("instruction", step.instruction)
      .put("video_prompt", step.videoPrompt)
      .put("sample_video", step.sampleVideo)
      .put("sample_preview", step.samplePreview)
    return JSONObject().put("template", "lesson_video").put("params", params).toString()
  }

  /** "segment": what to do (the user sees it), the default thing to find, and the sample photo for one-tap. */
  private fun buildSegmentCardSpec(step: LessonStep): String {
    val params = JSONObject()
      .put("instruction", step.instruction)
      .put("segment_prompt", step.segmentPrompt)
      .put("sample_image", step.sampleImage)
    return JSONObject().put("template", "lesson_segment").put("params", params).toString()
  }

  /**
   * The result card: the photo that was sent, in the grid SAM answered in, and a box per match. Each box
   * keeps the `w`/`h` the model reported — the drawing scales by those, not by what we believe we sent.
   *
   * Masks are NOT stored here: a raw payload is tens of kilobytes per object, and what to do with masks
   * is stage 2. At most [MAX_RESULT_BOXES] boxes are kept; `total` still says how many there were.
   */
  private fun buildSegmentResultCardSpec(found: SegmentationResult.Success, photo: PreparedPhoto): String {
    val boxes = JSONArray()
    found.records.take(MAX_RESULT_BOXES).forEach { record ->
      val box = record.box
      val json = JSONObject()
        .put("id", record.id)
        .put("x1", box.x1).put("y1", box.y1).put("x2", box.x2).put("y2", box.y2)
        .put("w", box.width).put("h", box.height)
      box.confidence?.let { json.put("c", it) }
      boxes.put(json)
    }
    val params = JSONObject()
      .put("image_url", Uri.fromFile(photo.file).toString())
      .put("image_w", photo.width)
      .put("image_h", photo.height)
      .put("concept", found.concept)
      .put("total", found.records.size)
      .put("boxes", boxes)
    return JSONObject().put("template", "lesson_segment_result").put("params", params).toString()
  }

  // imageKey is the short form: lessons/images/{key}.svg. An explicit asset takes priority.
  private fun LessonStep.bundledImageAsset(): String =
    asset.ifBlank { imageKey.takeIf { it.isNotBlank() }?.let { "lessons/images/$it.svg" } ?: "" }

  /** Drawing makes sense only for an image step with a prompt and no asset of its own. */
  private fun LessonStep.needsGeneratedImage(): Boolean =
    type == "image" && imagePrompt.isNotBlank() && bundledImageAsset().isBlank()

  /** "reveal": a question; tapping reveals the answer. */
  private fun buildRevealCardSpec(step: LessonStep): String {
    val params = JSONObject().put("question", step.question).put("answer", step.answer)
    return JSONObject().put("template", "reveal").put("params", params).toString()
  }

  /** "concept": term + definition + example. */
  private fun buildConceptCardSpec(step: LessonStep): String {
    val params = JSONObject()
      .put("term", step.term)
      .put("definition", step.definition)
      .put("example", step.example)
    return JSONObject().put("template", "concept").put("params", params).toString()
  }

  /** "example": a heading + a breakdown into parts (text/role/note). */
  private fun buildExampleCardSpec(step: LessonStep): String {
    val parts = JSONArray()
    step.parts.forEach { p ->
      parts.put(JSONObject().put("text", p.text).put("role", p.role).put("note", p.note))
    }
    val params = JSONObject().put("label", step.label).put("parts", parts)
    return JSONObject().put("template", "example").put("params", params).toString()
  }

  /**
   * "fill": a sentence with a `___` gap; the answer is checked against [LessonStep.answer] and the
   * accepted variants [LessonStep.answers] (empty strings are dropped — they would accept any input).
   */
  private fun buildFillCardSpec(step: LessonStep): String {
    val params = JSONObject()
      .put("template", step.template)
      .put("answer", step.answer)
      .put("answers", JSONArray(step.answers.filter { it.isNotBlank() }))
      .put("case_sensitive", step.caseSensitive)
    return JSONObject().put("template", "fill").put("params", params).toString()
  }

  /** "sort": put the items in the right order (correct is an array of target indexes). */
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

  /** "match": match terms with definitions. */
  private fun buildMatchCardSpec(step: LessonStep): String {
    val pairs = JSONArray()
    step.pairs.forEach { p ->
      pairs.put(JSONObject().put("term", p.term).put("def", p.def))
    }
    val params = JSONObject().put("instruction", step.instruction).put("pairs", pairs)
    return JSONObject().put("template", "match").put("params", params).toString()
  }

  /** "scenario": a situational question with one right option (correct is an integer). */
  private fun buildScenarioCardSpec(step: LessonStep): String {
    // -1, not 0: with a missing/broken correct the first option is not marked as "right".
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

    /** A crowd photo can return hundreds of objects; a card with that many frames is unreadable. */
    const val MAX_RESULT_BOXES = 50
  }
}
