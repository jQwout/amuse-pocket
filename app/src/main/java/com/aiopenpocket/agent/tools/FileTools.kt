package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.app.Application
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.IOException

/**
 * Resolves the file-tool sandbox root for a turn (Task 6 of the user-agent-builder plan): the
 * shared `filesDir/agent` plain chat always used, or `filesDir/agents/{agentId}` for a bound
 * custom agent — a private root no other agent (and no plain chat) shares. [agentId] is
 * [com.aiopenpocket.agent.core.agent.AgentDefinition.id] and is expected to already be checked
 * against [AgentValidator.ID_REGEX] by the time it gets here (both current callers — [FileTools]'s
 * own turn-bound agent and [com.aiopenpocket.data.repository.AgentFilesRepository.list] — pass an
 * already-resolved, already-validated id) — but this resolver re-checks it anyway and throws
 * [SecurityException] on a non-match instead of trusting the caller: a future caller could pass an
 * unvalidated string, and `agentId` becoming a path-traversal vector (a path separator or `..`
 * segment smuggled in as an "id") would defeat the containment [resolveSafePath] does for the
 * *path* argument, since that check only ever runs relative to whatever root this function hands
 * back. Fail-closed here means every caller, present or future, gets the guarantee for free instead
 * of having to remember to validate first.
 */
internal fun sandboxRoot(filesDir: File, agentId: String?): File {
  if (agentId != null && !AgentValidator.ID_REGEX.matches(agentId)) {
    throw SecurityException("Invalid agent id: $agentId")
  }
  return if (agentId == null) File(filesDir, "agent") else File(File(filesDir, "agents"), agentId)
}

@Inject
@SingleIn(AppScope::class)
class FileTools(
  private val application: Application,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  // Writes/deletes inside the sandbox; file contents can carry injected instructions.
  override val riskClass = RiskClass.WRITE
  override val returnsUntrustedContent = true

  // Per-call risk (Task 9): read_file/list_dir/search_files are read-only; only write_file
  // actually mutates the sandbox. An unrecognized/missing action fails closed to READ: execute()
  // below rejects it anyway ("Unknown action"), so nothing is actually written either way.
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "write_file" -> RiskClass.WRITE
    else -> RiskClass.READ
  }

  override val name = "file_tool"
  override val description = "Универсальный инструмент для работы с файлами: читает, пишет, показывает список файлов и ищет по содержимому. Используй только внутри sandbox."
  override val schema = toolSchema(name, description) {
    string(
      "action",
      "Действие: read_file, write_file, list_dir, search_files",
      required = true,
      enum = listOf("read_file", "write_file", "list_dir", "search_files"),
    )
    string("path", "Путь к файлу или директории относительно sandbox")
    string("content", "Содержимое для записи (только для write_file)")
    string("query", "Строка поиска (только для search_files)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = try {
    // Snapshot the sandbox root ONCE per call, from the turn's bound agent (or null for plain
    // chat) - every path op below uses this SAME root, even across a suspension inside this call.
    // Re-reading turnContextHolder.agent later in the call could observe a different snapshot than
    // the one this call started with (see TurnContextHolder's kdoc on why tools must not do that).
    val root = resolveSandboxDir()
    when (val action = arguments.string("action") ?: return missingArg("action")) {
      "read_file" -> readFile(root, arguments)
      "write_file" -> writeFile(root, arguments)
      "list_dir" -> listDir(root, arguments)
      "search_files" -> searchFiles(root, arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  } catch (e: SecurityException) {
    ToolResult.Error(e.message ?: "Access denied")
  }

  private fun resolveSandboxDir(): File =
    sandboxRoot(application.filesDir, turnContextHolder.agent?.id).also { if (!it.exists()) it.mkdirs() }

  private fun readFile(root: File, args: Map<String, JsonElement>): ToolResult {
    val path = args.string("path") ?: return missingArg("path")
    val file = resolveSafePath(root, path)
    if (!file.exists()) return ToolResult.Error("File not found: $path")
    if (!file.isFile) return ToolResult.Error("Not a file: $path")
    // A sandbox file is written by the agent itself, so its size is not limited by anything: without a
    // ceiling a single read_file both bloated the LLM context and pulled the whole file into memory.
    // The same trick as database_query with its MAX_ROWS — and, importantly, the truncation is
    // VISIBLE: a silently shortened file would be read by the model as complete, and it would draw
    // conclusions from half the data.
    val text = file.readText()
    if (text.length <= MAX_READ_CHARS) return ToolResult.Success(text)
    return ToolResult.Success(
      text.take(MAX_READ_CHARS) +
        "\n\n[усечено: показано $MAX_READ_CHARS из ${text.length} символов. " +
        "Используй search_files, чтобы найти нужный фрагмент.]",
    )
  }

  private fun writeFile(root: File, args: Map<String, JsonElement>): ToolResult {
    val path = args.string("path") ?: return missingArg("path")
    val content = args.string("content").orEmpty()
    val file = resolveSafePath(root, path)
    file.parentFile?.mkdirs()
    file.writeText(content)
    return ToolResult.Success("File written: $path")
  }

  private fun listDir(root: File, args: Map<String, JsonElement>): ToolResult {
    val path = args.string("path") ?: "."
    val dir = resolveSafePath(root, path)
    if (!dir.exists()) return ToolResult.Error("Directory not found: $path")
    if (!dir.isDirectory) return ToolResult.Error("Not a directory: $path")
    val all = dir.listFiles()
      ?.map { if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name}" }
      .orEmpty()
    // The directory is filled by the agent itself, so the number of entries is not limited by anything —
    // the same argument as for read_file/search_files below. The cut is stated explicitly: a silently
    // shortened list reads as "that is everything there is", and the model draws conclusions from an
    // incomplete directory.
    val shown = all.take(MAX_LIST_ENTRIES)
    val note = if (all.size > shown.size) "\n\n[показаны первые ${shown.size} из ${all.size} записей]" else ""
    return ToolResult.Success(shown.joinToString("\n") + note)
  }

  private fun searchFiles(root: File, args: Map<String, JsonElement>): ToolResult {
    val query = args.string("query") ?: return missingArg("query")
    val results = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    var skippedCount = 0
    var capped = false
    for (file in root.walkTopDown().filter { it.isFile }) {
      // The ceiling is checked AT THE START of an iteration, not after each addition: a name match has its
      // own branch with `continue`, and a check at the tail of the loop simply did not see it — a
      // thousand files matching by name came back whole, and the ceiling existed only on paper.
      if (results.size >= MAX_SEARCH_RESULTS) {
        capped = true
        break
      }
      val relative = file.relativeTo(root).path
      // A NAME match is checked first: it does not require reading the file at all, so a huge file is
      // still found by its name, just not by its content.
      if (file.name.contains(query, ignoreCase = true)) {
        results += relative
        continue
      }
      // readText() used to be called on EVERY sandbox file regardless of size — one big file (or a binary
      // written by the agent itself) dragged everything into memory at once.
      if (file.length() > MAX_SEARCH_FILE_BYTES) {
        skippedCount++
        if (skipped.size < MAX_SKIPPED_NAMES) skipped += relative
        continue
      }
      if (file.readText().contains(query, ignoreCase = true)) results += relative
    }
    // What was skipped is named explicitly: "not found" and "not searched" are different answers, and the
    // model must tell them apart, otherwise truncation reads as no result. But the footnote itself is
    // capped too: "name everything skipped" on a sandbox with a hundred large files would bloat the
    // output exactly as the missing ceiling used to.
    val skippedNote = when {
      skippedCount == 0 -> ""
      else -> {
        val names = skipped.joinToString(", ")
        val more = if (skippedCount > skipped.size) " и ещё ${skippedCount - skipped.size}" else ""
        "\n\n[пропущено по размеру (>${MAX_SEARCH_FILE_BYTES / 1024} КБ), содержимое не просмотрено: $names$more]"
      }
    }
    val cappedNote = if (capped) "\n\n[показаны первые $MAX_SEARCH_RESULTS совпадений — уточни запрос]" else ""
    val body = if (results.isEmpty()) "No files found matching '$query'" else results.joinToString("\n")
    return ToolResult.Success(body + cappedNote + skippedNote)
  }

  private fun resolveSafePath(root: File, path: String): File {
    // Backslash is not a separator on Android (Linux): there "..\agent-b" is just a folder name, while on
    // a Windows JVM it climbs up. One rule for both: such paths are refused, the separator is '/'.
    if ('\\' in path) throw SecurityException("Path traversal detected: $path")
    val rootCanonical = root.canonicalPath
    // An absolute path into the app's own data names a real place on the device: our sandbox is taken
    // as is, anything else there (another agent's sandbox, the shared one) is refused. Without this,
    // Linux silently re-rooted it under our sandbox as nested folders. Short "/notes.txt" never points
    // into app data and still counts from the sandbox root.
    val appData = application.filesDir.parentFile?.canonicalPath
    if (path.startsWith('/') && appData != null) {
      val absolute = canonicalOrNull(File(path)) ?: throw SecurityException("Path traversal detected: $path")
      if (absolute.isInside(appData)) {
        if (absolute.isInside(rootCanonical)) return File(absolute)
        throw SecurityException("Path traversal detected: $path")
      }
    }
    val normalized = path.trimStart('/')
    val file = File(root, normalized)
    val fileCanonical = try {
      file.canonicalPath
    } catch (e: IOException) {
      // A path that can't even be canonicalized (e.g. a Windows drive-absolute string embedded as
      // a child segment, like "C:\Windows\win.ini" under a relative root) can't be proven to stay
      // inside the sandbox either - fail closed instead of letting the IOException escape the tool.
      throw SecurityException("Path traversal detected: $path")
    }
    if (!fileCanonical.isInside(rootCanonical)) {
      throw SecurityException("Path traversal detected: $path")
    }
    return file
  }

  private fun canonicalOrNull(file: File): String? =
    try {
      file.canonicalPath
    } catch (e: IOException) {
      null
    }

  private fun String.isInside(dirCanonical: String): Boolean =
    this == dirCanonical || startsWith(dirCanonical + File.separator)

  companion object {
    /**
     * Ceiling of a `read_file` answer in characters — roughly as much coherent text as still makes
     * sense to put into the model's context in one call. More than that is no longer "read a file" but
     * "dump everything into the prompt".
     */
    const val MAX_READ_CHARS = 40_000

    /** Above this size `search_files` does not read the content (search by file name keeps working). */
    const val MAX_SEARCH_FILE_BYTES = 256L * 1024

    /** More matches per model call are not needed anyway — let it refine the query. */
    const val MAX_SEARCH_RESULTS = 100

    /** How many files skipped for size to name one by one; the rest are given as a count. */
    const val MAX_SKIPPED_NAMES = 10

    /** Ceiling of entries in a `list_dir` answer. */
    const val MAX_LIST_ENTRIES = 200
  }
}
