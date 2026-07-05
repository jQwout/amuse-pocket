package com.aiopenpocket.agent.tools

import android.app.Application
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import dev.zacsweers.metro.Inject
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.SingleIn

@Inject
@SingleIn(AppScope::class)
class FileTools(
  private val application: Application,
) : Tool {

  // Writes/deletes inside the sandbox; file contents can carry injected instructions.
  override val riskClass = RiskClass.WRITE
  override val returnsUntrustedContent = true

  private val sandboxDir: File
    get() = File(application.filesDir, "agent").also { if (!it.exists()) it.mkdirs() }

  override val name = "file_tool"
  override val description = "Универсальный инструмент для работы с файлами: читает, пишет, показывает список файлов и ищет по содержимому. Используй только внутри sandbox."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("read_file"), JsonPrimitive("write_file"), JsonPrimitive("list_dir"), JsonPrimitive("search_files"))),
            "description" to JsonPrimitive("Действие: read_file, write_file, list_dir, search_files"),
          )),
          "path" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Путь к файлу или директории относительно sandbox"),
          )),
          "content" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Содержимое для записи (только для write_file)"),
          )),
          "query" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Строка поиска (только для search_files)"),
          )),
        )),
        "required" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("action"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    return try {
      val action = (arguments["action"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'action'")
      when (action) {
        "read_file" -> readFile(arguments)
        "write_file" -> writeFile(arguments)
        "list_dir" -> listDir(arguments)
        "search_files" -> searchFiles(arguments)
        else -> ToolResult.Error("Unknown action: $action")
      }
    } catch (e: SecurityException) {
      ToolResult.Error(e.message ?: "Access denied")
    }
  }

  private fun readFile(args: Map<String, JsonElement>): ToolResult {
    val path = (args["path"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'path'")
    val file = resolveSafePath(path)
    if (!file.exists()) return ToolResult.Error("File not found: $path")
    if (!file.isFile) return ToolResult.Error("Not a file: $path")
    return ToolResult.Success(file.readText())
  }

  private fun writeFile(args: Map<String, JsonElement>): ToolResult {
    val path = (args["path"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'path'")
    val content = (args["content"] as? JsonPrimitive)?.content ?: ""
    val file = resolveSafePath(path)
    file.parentFile?.mkdirs()
    file.writeText(content)
    return ToolResult.Success("File written: $path")
  }

  private fun listDir(args: Map<String, JsonElement>): ToolResult {
    val path = (args["path"] as? JsonPrimitive)?.content ?: "."
    val dir = resolveSafePath(path)
    if (!dir.exists()) return ToolResult.Error("Directory not found: $path")
    if (!dir.isDirectory) return ToolResult.Error("Not a directory: $path")
    val children = dir.listFiles()?.map { if (it.isDirectory) "[DIR] ${it.name}" else "[FILE] ${it.name}" } ?: emptyList()
    return ToolResult.Success(children.joinToString("\n"))
  }

  private fun searchFiles(args: Map<String, JsonElement>): ToolResult {
    val query = (args["query"] as? JsonPrimitive)?.content ?: return ToolResult.Error("Missing 'query'")
    val results = mutableListOf<String>()
    sandboxDir.walkTopDown().filter { it.isFile }.forEach { file ->
      if (file.name.contains(query, ignoreCase = true) || file.readText().contains(query, ignoreCase = true)) {
        results.add(file.relativeTo(sandboxDir).path)
      }
    }
    return if (results.isEmpty()) {
      ToolResult.Success("No files found matching '$query'")
    } else {
      ToolResult.Success(results.joinToString("\n"))
    }
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
