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
    // Файл в песочнице пишет сам агент, поэтому его размер ничем не ограничен: без потолка одно
    // read_file и раздувало контекст LLM, и тянуло весь файл в память. Тот же приём, что у
    // database_query с его MAX_ROWS — и, главное, усечение ВИДИМОЕ: молча укороченный файл модель
    // прочитала бы как полный и сделала вывод по половине данных.
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
    val children = dir.listFiles()
      ?.map { if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name}" }
      .orEmpty()
    return ToolResult.Success(children.joinToString("\n"))
  }

  private fun searchFiles(root: File, args: Map<String, JsonElement>): ToolResult {
    val query = args.string("query") ?: return missingArg("query")
    val results = mutableListOf<String>()
    val skipped = mutableListOf<String>()
    for (file in root.walkTopDown().filter { it.isFile }) {
      val relative = file.relativeTo(root).path
      // Совпадение по ИМЕНИ проверяем первым: оно не требует читать файл вообще, поэтому огромный
      // файл всё ещё находится по названию, просто не по содержимому.
      if (file.name.contains(query, ignoreCase = true)) {
        results += relative
        continue
      }
      // Раньше readText() звался на КАЖДОМ файле песочницы без оглядки на размер — один большой файл
      // (или бинарник, записанный самим агентом) утаскивал в память всё сразу.
      if (file.length() > MAX_SEARCH_FILE_BYTES) {
        skipped += relative
        continue
      }
      if (file.readText().contains(query, ignoreCase = true)) results += relative
      if (results.size >= MAX_SEARCH_RESULTS) break
    }
    // Пропущенное называем явно: «не найдено» и «не искали» — разные ответы, и модель должна их
    // различать, иначе усечение читается как отсутствие результата.
    val skippedNote = if (skipped.isEmpty()) {
      ""
    } else {
      "\n\n[пропущено по размеру (>${MAX_SEARCH_FILE_BYTES / 1024} КБ), содержимое не просмотрено: " +
        skipped.joinToString(", ") + "]"
    }
    val body = if (results.isEmpty()) "No files found matching '$query'" else results.joinToString("\n")
    return ToolResult.Success(body + skippedNote)
  }

  private fun resolveSafePath(root: File, path: String): File {
    val normalized = path.trimStart('/', '\\')
    val file = File(root, normalized)
    val rootCanonical = root.canonicalPath
    val fileCanonical = try {
      file.canonicalPath
    } catch (e: IOException) {
      // A path that can't even be canonicalized (e.g. a Windows drive-absolute string embedded as
      // a child segment, like "C:\Windows\win.ini" under a relative root) can't be proven to stay
      // inside the sandbox either - fail closed instead of letting the IOException escape the tool.
      throw SecurityException("Path traversal detected: $path")
    }
    if (!fileCanonical.startsWith(rootCanonical + File.separator) && fileCanonical != rootCanonical) {
      throw SecurityException("Path traversal detected: $path")
    }
    return file
  }

  companion object {
    /**
     * Потолок выдачи `read_file` в символах — примерно столько связного текста ещё осмысленно класть
     * в контекст модели за один вызов. Больше — это уже не «прочитать файл», а «залить в промпт всё».
     */
    const val MAX_READ_CHARS = 40_000

    /** Крупнее — `search_files` не читает содержимое (поиск по имени файла продолжает работать). */
    const val MAX_SEARCH_FILE_BYTES = 256L * 1024

    /** То же в символах — для тестов, которым нужно построить заведомо «крупный» файл. */
    const val MAX_SEARCH_FILE_CHARS = 256 * 1024

    /** Больше совпадений за один вызов модели всё равно не нужны — пусть уточнит запрос. */
    const val MAX_SEARCH_RESULTS = 100
  }
}
