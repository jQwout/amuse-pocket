package com.aiopenpocket.agent.core.lesson

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Картинки уроков рисуются в двух местах: заранее скриптом `tools/lesson-images/pregenerate.py`
 * (они едут в сборке) и на телефоне [LessonImageGenerator] (для шагов без ассета). Стиль промпта у
 * них обязан совпадать дословно — иначе в одном уроке окажутся картинки из разных книжек, и
 * заметно это станет только глазами на устройстве.
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
