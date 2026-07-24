package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
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
import kotlinx.coroutines.CancellationException
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

    // The gate runs BEFORE any resolver call, for every action — see [rejectionReason].
    rejectionReason(uri, isWrite = action == "write_file")?.let { return ToolResult.Error(it) }

    return when (action) {
      "read_file" -> readFile(uri)
      "write_file" -> writeFile(uri, arguments.string("content").orEmpty())
      "list_dir" -> listDir(uri)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  /**
   * Null iff [uri] is a `content://` URI this app has actually been granted — every other outcome
   * returns a human-readable reason instead of a bare boolean, so the model is told exactly why its
   * URI was refused. FAIL-CLOSED: any URI that cannot be positively PROVEN reachable is rejected.
   *
   * Both checks matter, and neither subsumes the other:
   *
   * 1. **Scheme.** [ContentResolver.openInputStream]/[ContentResolver.openOutputStream] accept
   *    `file://` just as readily as `content://`, and this tool's `uri` argument is written by the
   *    MODEL — untrusted input. Without this check, `file:///data/data/{pkg}/databases/...` read the
   *    app's own private storage: straight around [FileTools]' canonical-path sandbox and around
   *    [DatabaseQueryTool]'s table allowlist, whose whole purpose is keeping `user_agents` (agent
   *    system prompts), `messages` and `facts` unreachable from a tool. A path traversal check like
   *    [FileTools]' cannot help here, because the argument isn't a path relative to any root.
   *
   * 2. **Grant.** Scheme alone is not enough: a `content://` URI naming some other app's provider is
   *    still a URI this app was never handed. [Context.checkUriPermission] is the platform's own
   *    answer to "may I actually touch this?" — it covers persisted SAF grants, transient grants, and
   *    document URIs derived from a granted TREE (the DocumentsProvider resolves that itself), so
   *    there is no tree-ancestry matching to hand-roll and get subtly wrong here.
   *
   * Note the practical consequence today: this app has no SAF picker at all (no
   * `ACTION_OPEN_DOCUMENT`/`takePersistableUriPermission` anywhere), so nothing has ever granted it a
   * document URI and every call lands on check 2. That is the honest state of the tool — its only
   * previously "working" path was the `file://` bypass this gate closes. When a picker is added, the
   * grants it takes make this gate pass on their own; nothing here needs revisiting.
   */
  private fun rejectionReason(uri: Uri, isWrite: Boolean): String? {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
      return "Only content:// URIs are allowed (got scheme '${uri.scheme ?: "none"}'). " +
        "Файлы приложения доступны через file_tool, а не здесь."
    }
    val modeFlags = if (isWrite) {
      Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    } else {
      Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    val granted = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), modeFlags) ==
      PackageManager.PERMISSION_GRANTED
    if (!granted) {
      return "No ${if (isWrite) "write" else "read"} access has been granted to this content:// URI. " +
        "Пользователь должен сначала выбрать файл или папку через системный диалог."
    }
    return null
  }

  private fun readFile(uri: Uri): ToolResult = try {
    context.contentResolver.openInputStream(uri)?.use { stream ->
      ToolResult.Success(stream.bufferedReader().readText())
    } ?: ToolResult.Error("Cannot open input stream for URI")
  } catch (e: CancellationException) {
    // Отмена хода — не ошибка ввода-вывода: пробрасываем, чтобы ToolExecutor остановил цикл.
    throw e
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
  } catch (e: CancellationException) {
    // Отмена хода — не ошибка ввода-вывода: пробрасываем, чтобы ToolExecutor остановил цикл.
    throw e
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
  } catch (e: CancellationException) {
    // Отмена хода — не ошибка ввода-вывода: пробрасываем, чтобы ToolExecutor остановил цикл.
    throw e
  } catch (e: Exception) {
    ToolResult.Error("List error: ${e.message}")
  }
}
