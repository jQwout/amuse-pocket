package com.aiopenpocket.agent.core.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract for the spaced-repetition interval math (kept out of the LLM prompt on purpose). */
class SrsSchedulerTest {

  @Test
  fun correctAnswerPromotesStageUntilCapped() {
    assertEquals(1, SrsScheduler.nextStage(0, correct = true))
    assertEquals(2, SrsScheduler.nextStage(1, correct = true))
    // Cannot exceed MAX_STAGE.
    assertEquals(SrsScheduler.MAX_STAGE, SrsScheduler.nextStage(SrsScheduler.MAX_STAGE, correct = true))
  }

  @Test
  fun wrongAnswerResetsToStageZero() {
    assertEquals(0, SrsScheduler.nextStage(3, correct = false))
    assertEquals(0, SrsScheduler.nextStage(0, correct = false))
  }

  @Test
  fun intervalGrowsWithStageAndIsClamped() {
    assertEquals(1, SrsScheduler.intervalDaysForStage(0))
    assertEquals(30, SrsScheduler.intervalDaysForStage(SrsScheduler.MAX_STAGE))
    // Out-of-range stages clamp instead of throwing.
    assertEquals(1, SrsScheduler.intervalDaysForStage(-5))
    assertEquals(30, SrsScheduler.intervalDaysForStage(999))
  }

  @Test
  fun nextDueIsIntervalDaysAfterReviewDay() {
    val today = 1_000_000_000_000L // arbitrary start-of-day
    // Stage 0 → +1 day.
    assertEquals(today + SrsScheduler.DAY_MS, SrsScheduler.nextDueMillis(today, 0))
    // Stage 2 → +7 days.
    assertEquals(today + 7 * SrsScheduler.DAY_MS, SrsScheduler.nextDueMillis(today, 2))
  }

  @Test
  fun isDueCoversPastAndTodayButNotFuture() {
    val today = 1_000_000_000_000L
    assertTrue(SrsScheduler.isDue(today - SrsScheduler.DAY_MS, today)) // overdue
    assertTrue(SrsScheduler.isDue(today, today)) // due today
    assertFalse(SrsScheduler.isDue(today + SrsScheduler.DAY_MS, today)) // not yet
  }

  @Test
  fun fullCycleWrongThenCorrectStreak() {
    // Simulate: new word (stage 0) → wrong (stays 0) → correct x3 → stage 3.
    var stage = 0
    stage = SrsScheduler.nextStage(stage, correct = false)
    assertEquals(0, stage)
    repeat(3) { stage = SrsScheduler.nextStage(stage, correct = true) }
    assertEquals(3, stage)
    assertEquals(14, SrsScheduler.intervalDaysForStage(stage))
  }
}
