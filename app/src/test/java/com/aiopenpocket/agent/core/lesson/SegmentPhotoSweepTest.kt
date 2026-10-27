package com.aiopenpocket.agent.core.lesson

// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The learner's photos must not stay in the cache for good. The camera original is deleted right after the analysis,
 * and what that misses (a process death, a closed screen) is swept by age; the compressed copies behind the result
 * cards are swept by age and by count. The clock is passed in and the files' times are set by hand.
 */
class SegmentPhotoSweepTest {

  @get:Rule
  val tmp = TemporaryFolder()

  private val now = 1_000_000_000_000L
  private val hour = 60L * 60 * 1000

  /** A file last modified [ageMs] before [now]. */
  private fun photo(dir: File, name: String, ageMs: Long): File =
    File(dir, name).also {
      it.writeBytes(byteArrayOf(1, 2, 3))
      assertTrue(it.setLastModified(now - ageMs))
    }

  @Test
  fun `files older than the limit go and fresh ones stay`() {
    val dir = tmp.newFolder()
    val old = photo(dir, "old.jpg", 2 * hour)
    val fresh = photo(dir, "fresh.jpg", 10 * 60 * 1000)

    val deleted = sweepPhotoFolder(dir, now, maxAgeMs = hour)

    assertEquals(1, deleted)
    assertFalse(old.exists())
    assertTrue(fresh.exists())
  }

  /** Exactly at the limit is not yet too old: a file written a moment ago must never be swept from under a running call. */
  @Test
  fun `a file exactly at the limit stays`() {
    val dir = tmp.newFolder()
    val edge = photo(dir, "edge.jpg", hour)
    val over = photo(dir, "over.jpg", hour + 1)

    sweepPhotoFolder(dir, now, maxAgeMs = hour)

    assertTrue(edge.exists())
    assertFalse(over.exists())
  }

  @Test
  fun `only the newest few are kept, even when none is old`() {
    val dir = tmp.newFolder()
    // Ages 1..7 minutes: all fresh, but only the five newest may stay.
    val files = (1..7).map { photo(dir, "p$it.jpg", it * 60_000L) }

    val deleted = sweepPhotoFolder(dir, now, maxAgeMs = 24 * hour, keepNewest = 5)

    assertEquals(2, deleted)
    assertEquals(listOf("p1.jpg", "p2.jpg", "p3.jpg", "p4.jpg", "p5.jpg"), dir.list()!!.sorted())
    // The oldest went, not the newest.
    assertFalse(files[6].exists())
    assertFalse(files[5].exists())
    assertTrue(files[0].exists())
  }

  @Test
  fun `age and count both apply`() {
    val dir = tmp.newFolder()
    photo(dir, "a.jpg", 1 * hour)
    photo(dir, "b.jpg", 2 * hour)
    photo(dir, "c.jpg", 3 * hour)
    photo(dir, "d.jpg", 30 * hour) // too old

    val deleted = sweepPhotoFolder(dir, now, maxAgeMs = 24 * hour, keepNewest = 2)

    assertEquals(2, deleted)
    assertEquals(listOf("a.jpg", "b.jpg"), dir.list()!!.sorted())
  }

  @Test
  fun `a missing folder is nothing to sweep and a subfolder is left alone`() {
    assertEquals(0, sweepPhotoFolder(File(tmp.root, "does-not-exist"), now, maxAgeMs = hour))

    val dir = tmp.newFolder()
    val nested = File(dir, "nested").also { it.mkdir() }
    assertTrue(nested.setLastModified(now - 100 * hour))
    sweepPhotoFolder(dir, now, maxAgeMs = hour, keepNewest = 0)
    assertTrue(nested.isDirectory)
  }

  @Test
  fun `the camera folder rule leaves a photo that is in flight`() {
    // The VM sweeps the camera folder with the camera limit and no count limit: a photo taken a moment ago, which a
    // call is still analysing, must survive however many there are.
    val dir = tmp.newFolder()
    val inFlight = (1..8).map { photo(dir, "capture-$it.jpg", it * 60_000L) }
    val orphan = photo(dir, "capture-orphan.jpg", CAMERA_ORIGINAL_MAX_AGE_MS + hour)

    sweepPhotoFolder(dir, now, CAMERA_ORIGINAL_MAX_AGE_MS)

    assertTrue(inFlight.all { it.exists() })
    assertFalse(orphan.exists())
  }
}
