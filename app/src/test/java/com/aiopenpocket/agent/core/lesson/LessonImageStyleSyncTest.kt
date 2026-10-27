package com.aiopenpocket.agent.core.lesson

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lesson images are drawn in two places: ahead of time by the `tools/lesson-images/pregenerate.py` script
 * (they ship in the build) and on the phone by [LessonImageGenerator] (for steps without an asset). Their
 * prompt style must match verbatim — otherwise one lesson ends up with images from different books, and
 * that only becomes noticeable by eye on a device.
 */
class LessonImageStyleSyncTest {

  private val script: File = sequenceOf(
    File("../tools/lesson-images/pregenerate.py"),
    File("tools/lesson-images/pregenerate.py"),
  ).firstOrNull { it.isFile }
    ?: error("Не нашёл tools/lesson-images/pregenerate.py; рабочая директория: ${File(".").absolutePath}")

  @Test
  fun `the pre-generation script uses the same style as the app`() {
    val expected = "STYLE = \"$LESSON_IMAGE_STYLE\""
    assertTrue(
      "В ${script.path} нет строки $expected — поправь STYLE в скрипте под LESSON_IMAGE_STYLE",
      script.readText().contains(expected),
    )
  }
}
