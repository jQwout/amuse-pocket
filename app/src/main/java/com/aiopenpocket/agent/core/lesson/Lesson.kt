package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2
// @trace user-agent-builder-848153
// @trace kartinki-urokov-cherez-muse-image-fd6f8c
// @trace video-na-vhod-v-uroke-muse-spark-08ccdb
// @trace protsedurnye-animatsii-v-urokah-68b2bf
// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A lesson loaded from `assets/lessons/{id}.json`. The lesson is a linear list of typed
 * [steps][LessonStep] driven by code; the LLM only assists on `text` steps ("Ask again").
 */
@Serializable
data class Lesson(
  val id: String,
  val title: String = "",
  val description: String = "",
  val iconKey: String = "default",
  /** True for the lesson that doubles as first-run onboarding. */
  val firstRun: Boolean = false,
  val steps: List<LessonStep> = emptyList(),
)

/**
 * One step. [type] is the discriminator:
 * "text" | "language" | "persona" | "profile" | "quiz" | "image" | "video" | "segment" | "animation" |
 * "reveal" | "concept" | "example" | "fill" | "sort" | "match" | "scenario".
 * Unused fields stay at their defaults — kept flat so JSON parses without polymorphic setup.
 */
@Serializable
data class LessonStep(
  val type: String,
  /** Author text shown on a "text" step. */
  val text: String = "",
  /** Hint to fake-stream the text word-by-word (demonstrates streaming). */
  val stream: Boolean = false,
  /** Optional caption for setup steps (e.g. profile). */
  val note: String = "",
  /** Questions for a "quiz" step. */
  val questions: List<LessonQuizQuestion> = emptyList(),
  /**
   * "image" step: bundled asset path under `assets/` (raster or .svg), tried FIRST so the lesson
   * works offline. Example: "lessons/images/llm_path.svg".
   */
  val asset: String = "",
  /** "image" step: http(s) URL used as a FALLBACK when [asset] is empty or fails to load. */
  val url: String = "",
  /** "image" step: optional caption shown under the picture. */
  val caption: String = "",
  /** "image" step: shorthand for an asset under `lessons/images/{imageKey}.svg`. */
  val imageKey: String = "",
  /** "image" step: accessibility text; used as caption fallback. */
  val alt: String = "",
  /**
   * "image" step: the prompt for Muse Image. The image is drawn only if the step has no asset of its own
   * ([asset]/[imageKey]): the bundle works without the network and costs nothing. A drawn image is
   * cached on disk — going through the lesson again does not redraw it. See [LessonImageGenerator].
   */
  val imagePrompt: String = "",
  /**
   * "video" step: the task for the model about the clip the user films or picks ([instruction] is what
   * the user sees). Muse Spark analyzes the video, the answer lands in the chat. See
   * [LessonChatController.postVideoAnswer].
   */
  val videoPrompt: String = "",
  /**
   * "video" step: a sample clip in assets (mp4) and its preview. With them the step's main button is
   * "Show me an example": filming your own on a phone is hard (camera, permissions, light), and the
   * point of the lesson — what the model sees in the frames — shows on a ready-made clip too. Filming
   * your own stays as the second option.
   */
  val sampleVideo: String = "",
  val samplePreview: String = "",
  /**
   * "segment" step: the DEFAULT thing SAM 3.1 looks for in the learner's photo, shown in a field they can
   * change — ONE noun phrase in English («cup», «yellow school bus»); SAM does not take lists or commands.
   * [instruction] is what the learner reads. SAM draws a box round every match, and the answer lands in
   * the chat under the step. See [LessonChatController.postSegmentation].
   */
  val segmentPrompt: String = "",
  /**
   * "segment" step: a sample photo in assets for the "Show on an example" button — the same reasoning as
   * [sampleVideo]: taking a photo is a camera and permissions, while the point of the step shows on a
   * ready-made picture in one tap.
   */
  val sampleImage: String = "",
  /** "animation" step: which procedural scene to show (see [LESSON_ANIMATION_SCENES]); the caption is [caption]. */
  val scene: String = "",
  /** "reveal" step: prompt shown first; tap reveals [answer]. */
  val question: String = "",
  /** "reveal" / "fill" step: the answer (revealed text, or the word to fill). */
  val answer: String = "",
  /** "concept" step: term, its definition and a short example. */
  val term: String = "",
  val definition: String = "",
  val example: String = "",
  /** "example" step: a titled breakdown into [parts]. */
  val label: String = "",
  val parts: List<LessonExamplePart> = emptyList(),
  /** "fill" step: sentence with a `___` blank; [answer] is checked against the input. */
  val template: String = "",
  val caseSensitive: Boolean = false,
  /**
   * "fill" step: other answers that also count (case-insensitive and ignoring surrounding spaces, like
   * [answer]). In Russian a gap gets filled in different ways — «конкретики», «деталей», «конкретика» —
   * and an answer right in meaning used to fail the exact comparison: fill has no "Next" button, so the
   * lesson got stuck. [answer] stays canonical: it is shown after solving and after two misses.
   */
  val answers: List<String> = emptyList(),
  /** "sort" / "match" step: instruction shown above the interactive items. */
  val instruction: String = "",
  /** "sort" step: items to reorder. */
  val items: List<String> = emptyList(),
  /** "match" step: term↔definition pairs to connect. */
  val pairs: List<LessonMatchPair> = emptyList(),
  /** "scenario" step: a single situational question. */
  val situation: String = "",
  val q: String = "",
  val options: List<String> = emptyList(),
  /**
   * Polymorphic: "sort" expects an array of target indices (correct order),
   * "scenario" expects a single integer (index of the right option).
   */
  val correct: JsonElement? = null,
  /** "sort" / "scenario" step: explanation shown after solving. */
  val explain: String = "",
  /**
   * "input" / "challenge" steps (live-chat interaction) are NOT fully supported yet — for now they
   * render as a static prompt with a "Next" button. These fields hold their authored text.
   */
  val prompt: String = "",
  val hint: String = "",
  val task: String = "",
  /**
   * Optional CTA shown on THIS LESSON's completion card (Task 23), authored on its FINAL step by
   * convention. [com.aiopenpocket.agent.core.lesson.LessonChatController.postCompletion] reads it
   * off `lesson.steps.lastOrNull()` when building the `lesson_complete` card's `primary_action` —
   * every OTHER lesson leaves this null on its last step, so their completion card is byte-for-byte
   * unchanged. Today only `agent_blueprint` (the AI Academy capstone) sets it, to bridge into the
   * agent builder with a fresh draft tagged `AgentSourceContext.ACADEMY_CAPSTONE`.
   */
  val primaryAction: LessonCompletionAction? = null,
)

/**
 * [LessonStep.primaryAction] payload. `type` must be a value
 * [com.aiopenpocket.ui.chat.DrivenUiEngine] recognizes (currently only `"open_agent_builder"`) —
 * an unrecognized type is rejected at card-parse time as an error card rather than silently
 * ignored, and [com.aiopenpocket.agent.core.lesson.LessonAssetsValidationTest] lints every lesson
 * asset for the same rule so a typo fails the build instead of shipping a dead button.
 */
@Serializable
data class LessonCompletionAction(
  val type: String = "",
  val label: String = "",
)

/**
 * "scenario" step: 0-based index of the correct option, or -1 when the key is missing/malformed
 * (see f646e64 — a bad key must NOT fall back to 0 and falsely mark the first option correct).
 * Single decode of the polymorphic [LessonStep.correct], shared by the chat controller and the
 * asset lint so they can't disagree on what the renderer treats as the answer.
 */
fun LessonStep.scenarioCorrectIndex(): Int = (correct as? JsonPrimitive)?.intOrNull ?: -1

@Serializable
data class LessonQuizQuestion(
  val q: String,
  val options: List<String> = emptyList(),
  // -1, not 0: with a missing/broken correct the first option is not marked as "right"
  // (see commit f646e64, the same class of bug for sort/scenario).
  val correct: Int = -1,
  val explain: String = "",
)

/** One labelled fragment of an "example" step (e.g. a prompt part with its role and a note). */
@Serializable
data class LessonExamplePart(
  val text: String = "",
  val role: String = "",
  val note: String = "",
)

/** One term↔definition pair of a "match" step. */
@Serializable
data class LessonMatchPair(
  val term: String = "",
  val def: String = "",
)
