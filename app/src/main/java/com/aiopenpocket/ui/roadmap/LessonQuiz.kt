package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb

/** Single multiple-choice quiz question — the shape QuizContent renders for a lesson quiz card. */
data class QuizQuestion(
  val question: String,
  val options: List<String>,
  val correctIndex: Int,
  /** Shown after the user answers, regardless of correctness. */
  val explanation: String,
)
