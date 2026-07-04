package com.aiopenpocket.agent.core.srs

// @trace yazykovoy-agent-repetitor-s-intervalnymi-povtoreniyami-4a9360

/**
 * Pure spaced-repetition scheduling — the date/interval math that must NOT live in the LLM prompt
 * (models are unreliable at date arithmetic and silently corrupt card state). Works on plain
 * millis/stage integers so it is fully unit-testable without Android or Room.
 *
 * Leitner-style stages: a correct answer promotes the card one stage (longer interval), a wrong
 * answer resets it to stage 0 (review again tomorrow).
 */
object SrsScheduler {

  const val DAY_MS: Long = 24L * 60 * 60 * 1000

  /** Interval in days until the next review, indexed by stage. Stage is clamped into range. */
  val INTERVAL_DAYS: List<Int> = listOf(1, 3, 7, 14, 30)

  val MAX_STAGE: Int = INTERVAL_DAYS.lastIndex

  fun intervalDaysForStage(stage: Int): Int = INTERVAL_DAYS[stage.coerceIn(0, MAX_STAGE)]

  /** New stage after an answer: correct → +1 (capped at [MAX_STAGE]); incorrect → reset to 0. */
  fun nextStage(currentStage: Int, correct: Boolean): Int =
    if (correct) (currentStage + 1).coerceAtMost(MAX_STAGE) else 0

  /**
   * Next due timestamp given the start-of-day the review happened on and the resulting stage.
   * Anchoring to start-of-day keeps "due today" stable regardless of the time of the review.
   */
  fun nextDueMillis(reviewedStartOfDayMs: Long, newStage: Int): Long =
    reviewedStartOfDayMs + intervalDaysForStage(newStage) * DAY_MS

  /** A card is due when its due day is today or earlier. */
  fun isDue(dueMs: Long, todayStartOfDayMs: Long): Boolean = dueMs <= todayStartOfDayMs
}
