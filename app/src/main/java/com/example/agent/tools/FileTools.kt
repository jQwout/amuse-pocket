package com.aiopenpocket.agent.tools

import android.app.Application
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement
import java.io.File

@Inject
@SingleIn(AppScope::class)
class FileTools(
  private val application: Application,
) : Tool {

  private val sandboxDir: File
    get() = File(application.filesDir, "agent").also { if (!it.exists()) it.mkdirs() }

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
    when (val action = arguments.string("action") ?: return missingArg("action")) {
      "read_file" -> readFile(arguments)
      "write_file" -> writeFile(arguments)
      "list_dir" -> listDir(arguments)
      "search_files" -> searchFiles(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  } catch (e: SecurityException) {
    ToolResult.Error(e.message ?: "Access denied")
  }

  private fun readFile(args: Map<String, JsonElement>): ToolResult {
    val path = args.string("path") ?: return missingArg("path")
    val file = resolveSafePath(path)
    if (!file.exists()) return ToolResult.Error("File not found: $path")
    if (!file.isFile) return ToolResult.Error("Not a file: $path")
    return ToolResult.Success(file.readText())
  }

  private fun writeFile(args: Map<String, JsonElement>): ToolResult {
    val path = args.string("path") ?: return missingArg("path")
    val content = args.string("content").orEmpty()
    val file = resolveSafePath(path)
    file.parentFile?.mkdirs()
    file.writeText(content)
    return ToolResult.Success("File written: $path")
  }

  private fun listDir(args: Map<String, JsonElement>): ToolResult {
    val path = args.string("path") ?: "."
    val dir = resolveSafePath(path)
    if (!dir.exists()) return ToolResult.Error("Directory not found: $path")
    if (!dir.isDirectory) return ToolResult.Error("Not a directory: $path")
    val children = dir.listFiles()
      ?.map { if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name}" }
      .orEmpty()
    return ToolResult.Success(children.joinToString("\n"))
  }

  private fun searchFiles(args: Map<String, JsonElement>): ToolResult {
    val query = args.string("query") ?: return missingArg("query")
    val results = sandboxDir.walkTopDown()
      .filter { it.isFile }
      .filter { it.name.contains(query, ignoreCase = true) || it.readText().contains(query, ignoreCase = true) }
      .map { it.relativeTo(sandboxDir).path }
      .toList()
    return if (results.isEmpty()) ToolResult.Success("No files found matching '$query'")
    else ToolResult.Success(results.joinToString("\n"))
  }

  private fun resolveSafePath(path: String): File {
    val normalized = path.trimStart('/', '\\')
    val file = File(sandboxDir, normalized)
    val sandboxCanonical = sandboxDir.canonicalPath
    val fileCanonical = file.canonicalPath
    if (!fileCanonical.startsWith(sandboxCanonical + File.separator) && fileCanonical != sandboxCanonical) {
      throw SecurityException("Path traversal detected: $path")
    }
    return file
  }
}
