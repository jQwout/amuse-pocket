package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A lesson loaded from `assets/lessons/{id}.json`. The lesson is a linear list of typed
 * [steps][LessonStep] driven by code; the LLM only assists on `text` steps ("Переспросить").
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
 * "text" | "language" | "persona" | "profile" | "quiz" | "image" |
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
   * render as a static prompt with a "Дальше" button. These fields hold their authored text.
   */
  val prompt: String = "",
  val hint: String = "",
  val task: String = "",
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
  // -1, а не 0: при отсутствующем/битом correct не помечаем первый вариант как «правильный»
  // (см. коммит f646e64, тот же класс бага для sort/scenario).
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
