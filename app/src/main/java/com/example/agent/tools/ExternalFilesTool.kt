package com.aiopenpocket.agent.tools

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader

@Inject
@SingleIn(AppScope::class)
class ExternalFilesTool(
  private val context: Context,
) : Tool {

  override val name = "external_files"
  override val description =
    "Читает, пишет и листит файлы по URI через Storage Access Framework (SAF). " +
    "Используй только URI, к которым у приложения есть доступ (например, полученные через системный диалог выбора файла)."
  override val schema = FunctionSchema(
    function = FunctionSchema.FunctionDefinition(
      name = name,
      description = description,
      parameters = JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(mapOf(
          "action" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "enum" to JsonPrimitive("read_file,write_file,list_dir"),
            "description" to JsonPrimitive("Действие: read_file, write_file, list_dir"),
          )),
          "uri" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("URI файла или директории (content://...)"),
          )),
          "content" to JsonObject(mapOf(
            "type" to JsonPrimitive("string"),
            "description" to JsonPrimitive("Содержимое для записи (только write_file)"),
          )),
        )),
        "required" to JsonArray(listOf(JsonPrimitive("action"), JsonPrimitive("uri"))),
      )),
    ),
  )

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = (arguments["action"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'action'")
    val uriString = (arguments["uri"] as? JsonPrimitive)?.content
      ?: return ToolResult.Error("Missing 'uri'")
    val uri = Uri.parse(uriString)

    return when (action) {
      "read_file" -> readFile(uri)
      "write_file" -> writeFile(uri, arguments)
      "list_dir" -> listDir(uri)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private fun readFile(uri: Uri): ToolResult {
    return try {
      context.contentResolver.openInputStream(uri)?.use { stream ->
        val reader = BufferedReader(InputStreamReader(stream))
        val content = reader.readText()
        ToolResult.Success(content)
      } ?: ToolResult.Error("Cannot open input stream for URI")
    } catch (e: Exception) {
      ToolResult.Error("Read error: ${e.message}")
    }
  }

  private fun writeFile(uri: Uri, args: Map<String, JsonElement>): ToolResult {
    val content = (args["content"] as? JsonPrimitive)?.content ?: ""
    return try {
      // Режим "wt" усекает файл; "w" на части DocumentsProvider не обрезает
      // и оставляет хвост старого содержимого при перезаписи более коротким текстом.
      context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
        stream.write(content.toByteArray())
        ToolResult.Success("File written successfully")
      } ?: ToolResult.Error("Cannot open output stream for URI")
    } catch (e: Exception) {
      ToolResult.Error("Write error: ${e.message}")
    }
  }

  private fun listDir(uri: Uri): ToolResult {
    return try {
      val docFile = DocumentFile.fromTreeUri(context, uri)
        ?: return ToolResult.Error("Invalid document tree URI")
      if (!docFile.isDirectory) return ToolResult.Error("URI is not a directory")
      val children = docFile.listFiles().map {
        val type = if (it.isDirectory) "[DIR]" else "[FILE]"
        "$type ${it.name}"
      }
      ToolResult.Success(children.joinToString("\n"))
    } catch (e: Exception) {
      ToolResult.Error("List error: ${e.message}")
    }
  }
}
