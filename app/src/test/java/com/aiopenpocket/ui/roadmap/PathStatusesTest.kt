package com.aiopenpocket.ui.roadmap

import org.junit.Assert.assertEquals
import org.junit.Test

class PathStatusesTest {

  private val order = listOf("a", "b", "c", "d")

  @Test
  fun `nothing done - the first lesson is next and every other one is open`() {
    assertEquals(
      mapOf("a" to LevelStatus.CURRENT, "b" to LevelStatus.OPEN, "c" to LevelStatus.OPEN, "d" to LevelStatus.OPEN),
      pathStatuses(order, emptySet()),
    )
  }

  @Test
  fun `a lesson done out of order keeps its tick and the next one is the first gap`() {
    // The capstone (here "d") can be done first: nothing in front of it is locked any more.
    assertEquals(
      mapOf("a" to LevelStatus.CURRENT, "b" to LevelStatus.OPEN, "c" to LevelStatus.OPEN, "d" to LevelStatus.COMPLETED),
      pathStatuses(order, setOf("d")),
    )
  }

  @Test
  fun `everything done - no lesson is next`() {
    assertEquals(order.associateWith { LevelStatus.COMPLETED }, pathStatuses(order, order.toSet()))
  }

  @Test
  fun `the real path has no locked lesson`() {
    val statuses = pathStatuses(ROADMAP_LEVELS.map { it.id }, emptySet())
    assertEquals(ROADMAP_LEVELS.size, statuses.size)
    assertEquals(1, statuses.values.count { it == LevelStatus.CURRENT })
  }
}
