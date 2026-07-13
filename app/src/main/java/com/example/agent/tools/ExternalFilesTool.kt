package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

@Inject
@SingleIn(AppScope::class)
class ExternalFilesTool(
  private val context: Context,
) : Tool {

  // Files chosen via SAF originate outside the app boundary.
  override val returnsUntrustedContent = true

  // Per-call risk (Task 9): same read/write split as file_tool - only write_file mutates the
  // target document. riskClass stays the WRITE ceiling for callers without per-call arguments (e.g.
  // episodic-memory logging in ReactLoopRunner); riskFor is what the policy gate actually uses.
  override val riskClass = RiskClass.WRITE

  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "write_file" -> RiskClass.WRITE
    else -> RiskClass.READ
  }

  override val name = "external_files"
  override val description =
    "Читает, пишет и листит файлы по URI через Storage Access Framework (SAF). " +
    "Используй только URI, к которым у приложения есть доступ (например, полученные через системный диалог выбора файла)."
  override val schema = toolSchema(name, description) {
    string(
      "action",
      "Действие: read_file, write_file, list_dir",
      required = true,
      enum = listOf("read_file", "write_file", "list_dir"),
    )
    string("uri", "URI файла или директории (content://...)", required = true)
    string("content", "Содержимое для записи (только write_file)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    val uri = Uri.parse(arguments.string("uri") ?: return missingArg("uri"))

    return when (action) {
      "read_file" -> readFile(uri)
      "write_file" -> writeFile(uri, arguments.string("content").orEmpty())
      "list_dir" -> listDir(uri)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private fun readFile(uri: Uri): ToolResult = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
      ToolResult.Success(stream.bufferedReader().readText())
    } ?: ToolResult.Error("Cannot open input stream for URI")
  } catch (e: Exception) {
    ToolResult.Error("Read error: ${e.message}")
  }

  private fun writeFile(uri: Uri, content: String): ToolResult = try {
    // Режим "wt" усекает файл; "w" на части DocumentsProvider не обрезает
    // и оставляет хвост старого содержимого при перезаписи более коротким текстом.
    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
      stream.write(content.toByteArray())
      ToolResult.Success("File written successfully")
    } ?: ToolResult.Error("Cannot open output stream for URI")
  } catch (e: Exception) {
    ToolResult.Error("Write error: ${e.message}")
  }

  private fun listDir(uri: Uri): ToolResult = try {
    val docFile = DocumentFile.fromTreeUri(context, uri)
      ?: return ToolResult.Error("Invalid document tree URI")
    if (!docFile.isDirectory) return ToolResult.Error("URI is not a directory")
    val children = docFile.listFiles().map {
      "${if (it.isDirectory) "[DIR]" else "[FILE]"} ${it.name}"
    }
    ToolResult.Success(children.joinToString("\n"))
  } catch (e: Exception) {
    ToolResult.Error("List error: ${e.message}")
  }
}
