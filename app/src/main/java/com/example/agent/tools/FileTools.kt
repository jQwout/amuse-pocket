package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.app.Application
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
 * [com.aiopenpocket.agent.core.agent.AgentDefinition.id], already checked against
 * `AgentValidator`'s `[a-z0-9][a-z0-9_-]{2,63}` regex before it can reach here, so it can't itself
 * carry a path separator or `..` segment — but callers must still canonicalize and verify every
 * resolved *file path* against this root, because the path argument passed to `file_tool` is
 * model-supplied, not validated the way the id is.
 */
internal fun sandboxRoot(filesDir: File, agentId: String?): File =
  if (agentId == null) File(filesDir, "agent") else File(File(filesDir, "agents"), agentId)

@Inject
@SingleIn(AppScope::class)
class FileTools(
  private val application: Application,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  // Writes/deletes inside the sandbox; file contents can carry injected instructions.
  override val riskClass = RiskClass.WRITE
  override val returnsUntrustedContent = true

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
    return ToolResult.Success(file.readText())
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
    val results = root.walkTopDown()
      .filter { it.isFile }
      .filter { it.name.contains(query, ignoreCase = true) || it.readText().contains(query, ignoreCase = true) }
      .map { it.relativeTo(root).path }
      .toList()
    return if (results.isEmpty()) ToolResult.Success("No files found matching '$query'")
    else ToolResult.Success(results.joinToString("\n"))
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
}
