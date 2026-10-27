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
 * Size ceilings in `file_tool`.
 *
 * `read_file` returned `file.readText()` whole, and `search_files` called `readText()` ON EVERY file in
 * the sandbox — without a size check. The agent writes the files there itself, so their size is not
 * limited by anything: that means both a memory spike and an instant blow-up of the LLM context. The
 * neighboring `database_query` cuts its output at 200 rows and honestly marks the truncation for exactly
 * this reason — here there was no ceiling at all.
 *
 * An important property of truncation: it must be VISIBLE. A silently shortened file is read by the model
 * as complete, and it draws conclusions from half the data.
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
    // The needle IS in the huge file — and it still must not get into the matches: its content was not read.
    // It goes into a separate note about skipped files (see the next test), so we check not that its name
    // is absent from the whole text, but that it is not passed off as a found match.
    sandboxFile("huge.bin", "x".repeat((FileTools.MAX_SEARCH_FILE_BYTES * 2).toInt()) + "игла")
    sandboxFile("small.txt", "игла")

    val result = tool.execute(args("action" to "search_files", "query" to "игла"))

    val text = result.toString()
    val matches = text.substringBefore("[пропущено")
    assertTrue("маленький файл должен находиться: $text", matches.contains("small.txt"))
    assertTrue("огромный файл не должен был читаться: $text", !matches.contains("huge.bin"))
  }

  @Test
  fun `search_files reports what it skipped instead of silently dropping it`() = runTest {
    sandboxFile("huge.bin", "x".repeat((FileTools.MAX_SEARCH_FILE_BYTES * 2).toInt()))
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

  // --- The match count ceiling applies to BOTH search paths ------------------------------------------

  @Test
  fun `matches found by name are capped too, not just matches found by content`() = runTest {
    // A name match does not need to read the file, so it has a separate branch — and exactly that branch
    // bypassed the ceiling check: an agent that piled a thousand files into the sandbox got them all.
    repeat(FileTools.MAX_SEARCH_RESULTS + 50) { i -> sandboxFile("заметка-$i.txt", "тело") }

    val result = tool.execute(args("action" to "search_files", "query" to "заметка"))

    val matchLines = result.toString().substringBefore("[").lines().filter { it.endsWith(".txt") }
    assertTrue("потолок не сработал: ${matchLines.size} совпадений", matchLines.size <= FileTools.MAX_SEARCH_RESULTS)
  }

  @Test
  fun `hitting the result cap is reported, not silently truncated`() = runTest {
    repeat(FileTools.MAX_SEARCH_RESULTS + 50) { i -> sandboxFile("заметка-$i.txt", "тело") }

    val result = tool.execute(args("action" to "search_files", "query" to "заметка"))

    assertTrue("обрезка молчаливая: $result", result.toString().contains("перв", ignoreCase = true))
  }

  @Test
  fun `the skipped-by-size note cannot itself grow without bound`() = runTest {
    // The opposite extreme: "name what was skipped explicitly" must not turn into a dump of a thousand names.
    val big = "x".repeat((FileTools.MAX_SEARCH_FILE_BYTES + 1024).toInt())
    repeat(40) { i -> sandboxFile("огромный-$i.bin", big) }
    sandboxFile("small.txt", "игла")

    val result = tool.execute(args("action" to "search_files", "query" to "игла"))

    val note = result.toString().substringAfter("[пропущено", "")
    assertTrue("сноска о пропуске не ограничена: ${note.length} символов", note.length < 500)
  }

  @Test
  fun `list_dir on a huge directory is bounded as well`() = runTest {
    repeat(400) { i -> sandboxFile("файл-$i.txt", "тело") }

    val result = tool.execute(args("action" to "list_dir", "path" to "."))

    val lines = result.toString().lines().filter { it.startsWith("[FILE]") }
    assertTrue("список каталога не ограничен: ${lines.size} строк", lines.size <= FileTools.MAX_LIST_ENTRIES)
  }
}
