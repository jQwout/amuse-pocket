package com.aiopenpocket.agent.core.lesson

import kotlinx.serialization.Serializable

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
 * One step. [type] is the discriminator: "text" | "language" | "persona" | "profile" | "quiz".
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
)

@Serializable
data class LessonQuizQuestion(
  val q: String,
  val options: List<String> = emptyList(),
  val correct: Int = 0,
  val explain: String = "",
)
