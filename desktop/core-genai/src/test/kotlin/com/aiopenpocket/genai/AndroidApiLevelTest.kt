package com.aiopenpocket.genai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Сторож совместимости с Android. Этот модуль по плану переезжает в общий код с `:app`, где
 * `minSdk = 24` и десугаринга нет, поэтому API уровня 26 в нём быть не должно.
 *
 * Цена ошибки не «не соберётся», а хуже: `java.nio.file` на Android 7.x даёт
 * `NoClassDefFoundError` — это `Error`, а не `Exception`, то есть он проходит мимо любых
 * `catch (e: Exception)` и роняет процесс. В `:app` на этом уже обожглись
 * (`OpenRouterCatalogCache`, третий круг ревью), и повторять незачем.
 *
 * Тест читает ИСХОДНИКИ, а не байт-код: на десктопной JVM запрещённые классы существуют и в
 * рантайме ничего не падает — увидеть проблему можно только в тексте программы.
 */
class AndroidApiLevelTest {

  private val forbidden = mapOf(
    "java.nio.file" to "нет на Android до API 26 — используй java.io.File",
    "java.util.Base64" to "нет на Android до API 26 — используй kotlin.io.encoding.Base64",
    "java.time." to "нет на Android до API 26 без десугаринга",
    "kotlin.io.path" to "обёртка над java.nio.file — те же API 26",
  )

  @Test
  fun `core module stays free of API 26 only APIs`() {
    val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()
    assertTrue(sources.isNotEmpty(), "исходники не найдены — тест смотрит не туда, а не «всё чисто»")

    val offenders = mutableListOf<String>()
    for (source in sources) {
      for ((number, code) in codeLines(source)) {
        for ((api, why) in forbidden) {
          if (api in code) offenders += "${source.name}:$number  $api — $why"
        }
      }
    }

    assertTrue(offenders.isEmpty(), "в общем ядре появились API 26:\n" + offenders.joinToString("\n"))
  }

  /**
   * Строки кода без комментариев. Блочные комментарии вырезаются полностью: в этом модуле как раз
   * в KDoc и объясняется, ПОЧЕМУ здесь нет `java.nio.file`, — наивная проверка ловила бы сама себя.
   */
  private fun codeLines(source: File): List<Pair<Int, String>> {
    val result = mutableListOf<Pair<Int, String>>()
    var inBlock = false
    source.readLines().forEachIndexed { index, raw ->
      var line = raw
      if (inBlock) {
        val end = line.indexOf("*/")
        if (end < 0) return@forEachIndexed
        line = line.substring(end + 2)
        inBlock = false
      }
      val blockStart = line.indexOf("/*")
      if (blockStart >= 0) {
        val end = line.indexOf("*/", blockStart + 2)
        line = if (end >= 0) line.removeRange(blockStart, end + 2) else {
          inBlock = true
          line.substring(0, blockStart)
        }
      }
      val code = line.substringBefore("//")
      if (code.isNotBlank()) result += (index + 1) to code
    }
    return result
  }
}
