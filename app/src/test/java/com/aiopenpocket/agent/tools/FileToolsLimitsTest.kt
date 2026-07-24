package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b

import android.app.Application
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.TurnContextHolder
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Потолки на объём у `file_tool`.
 *
 * `read_file` отдавал `file.readText()` целиком, а `search_files` вызывал `readText()` НА КАЖДОМ файле
 * песочницы — без проверки размера. Файл туда пишет сам агент, так что размер ничем не ограничен: это
 * и скачок памяти, и мгновенное раздувание контекста LLM. Соседний `database_query` ровно поэтому
 * режет выдачу на 200 строках и честно помечает усечение — здесь потолка не было вообще.
 *
 * Важное свойство усечения: оно должно быть ВИДИМЫМ. Молча укороченный файл модель прочитает как
 * полный и сделает вывод по половине данных.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileToolsLimitsTest {

  private lateinit var application: Application
  private lateinit var tool: FileTools

  @Before
  fun setUp() {
    application = RuntimeEnvironment.getApplication()
    tool = FileTools(application, TurnContextHolder().also { it.set("conv1", null) })
  }

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  private fun sandboxFile(name: String, content: String): File {
    val dir = File(application.filesDir, "agent").apply { mkdirs() }
    return File(dir, name).apply { writeText(content) }
  }

  @Test
  fun `read_file caps a huge file instead of returning all of it`() = runTest {
    sandboxFile("huge.txt", "я".repeat(FileTools.MAX_READ_CHARS * 2))

    val result = tool.execute(args("action" to "read_file", "path" to "huge.txt"))

    val text = (result as ToolResult.Success).data
    assertTrue("выдача не ограничена: ${text.length} символов", text.length < FileTools.MAX_READ_CHARS * 2)
  }

  @Test
  fun `a truncated read says so, so the model does not treat half a file as the whole`() = runTest {
    sandboxFile("huge.txt", "я".repeat(FileTools.MAX_READ_CHARS * 2))

    val result = tool.execute(args("action" to "read_file", "path" to "huge.txt"))

    assertTrue("усечение молчаливое: $result", result.toString().contains("усеч", ignoreCase = true))
  }

  @Test
  fun `a small file is returned byte for byte`() = runTest {
    sandboxFile("small.txt", "короткое содержимое")

    assertEquals(ToolResult.Success("короткое содержимое"), tool.execute(args("action" to "read_file", "path" to "small.txt")))
  }

  @Test
  fun `search_files does not read oversized files into memory`() = runTest {
    // В огромном файле игла ЕСТЬ — и он всё равно не должен попасть в совпадения: его содержимое не
    // читалось. Он попадёт в отдельную сноску о пропуске (см. следующий тест), поэтому проверяем не
    // отсутствие имени в тексте целиком, а то, что он не выдан за найденное совпадение.
    sandboxFile("huge.bin", "x".repeat(FileTools.MAX_SEARCH_FILE_CHARS * 2) + "игла")
    sandboxFile("small.txt", "игла")

    val result = tool.execute(args("action" to "search_files", "query" to "игла"))

    val text = result.toString()
    val matches = text.substringBefore("[пропущено")
    assertTrue("маленький файл должен находиться: $text", matches.contains("small.txt"))
    assertTrue("огромный файл не должен был читаться: $text", !matches.contains("huge.bin"))
  }

  @Test
  fun `search_files reports what it skipped instead of silently dropping it`() = runTest {
    sandboxFile("huge.bin", "x".repeat(FileTools.MAX_SEARCH_FILE_CHARS * 2))
    sandboxFile("small.txt", "игла")

    val result = tool.execute(args("action" to "search_files", "query" to "игла"))

    assertTrue("пропуск не отражён в выдаче: $result", result.toString().contains("пропущ", ignoreCase = true))
  }

  @Test
  fun `search_files still finds by file name`() = runTest {
    sandboxFile("заметки.txt", "неважно")

    val result = tool.execute(args("action" to "search_files", "query" to "заметки"))

    assertTrue(result.toString().contains("заметки.txt"))
  }
}
