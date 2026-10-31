package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every lesson's `iconKey` has an icon of its own in the library. A key nobody mapped does not fail
 * anywhere: it silently draws the generic Article icon, and nine lessons did exactly that.
 */
class LessonIconTest {

  private val lessonsDir: File = sequenceOf(File("src/main/assets/lessons"), File("app/src/main/assets/lessons"))
    .firstOrNull { it.isDirectory }
    ?: error("lessons assets not found; working dir: ${File(".").absolutePath}")

  /** lesson file name -> iconKey, for the Russian files and the English copies alike. */
  private fun iconKeys(): Map<String, String> {
    val files = lessonsDir.listFiles { f -> f.extension == "json" }.orEmpty().toList() +
      File(lessonsDir, "en").listFiles { f -> f.extension == "json" }.orEmpty().toList()
    return files.associate { f ->
      val key = Regex(""""iconKey"\s*:\s*"([^"]+)"""").find(f.readText())?.groupValues?.get(1)
        ?: error("${f.path} has no iconKey")
      f.parentFile!!.name + "/" + f.name to key
    }
  }

  @Test
  fun `every lesson iconKey has its own icon, none falls back to the generic one`() {
    val keys = iconKeys()
    assertTrue("no lesson files found", keys.size >= 30)

    val unmapped = keys.filterValues { lessonIconOrNull(it) == null }

    assertEquals("lessons whose iconKey has no icon: $unmapped", emptyMap<String, String>(), unmapped)
  }

  @Test
  fun `the nine keys that used to fall back are mapped`() {
    listOf(
      "smart_toy", "psychology", "account_tree", "alt_route", "sync",
      "alarm", "groups", "construction", "architecture",
    ).forEach { assertNotNull("no icon for $it", lessonIconOrNull(it)) }
  }

  @Test
  fun `a key nobody mapped is reported as unmapped`() {
    assertNull(lessonIconOrNull("no_such_icon"))
  }
}
