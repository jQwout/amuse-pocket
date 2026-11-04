package com.aiopenpocket.agent.core.llm

// @trace pdf-documents-in-chat-read-by-muse-spark-b7acf1

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.util.UUID

/**
 * PDF documents attached to chat messages, sent to Muse Spark as they are: Meta reads the PDF itself (text
 * from the first 100 pages, page images of the first 50), so the app neither parses nor converts it.
 *
 * A document rides the same message field as a photo (`imagePath`): the file's extension tells them apart,
 * so no schema change was needed. The copy keeps the original file name (inside its own random folder),
 * because the name is what the chat shows and what Meta is told (`filename`).
 *
 * The wire shape, from dev.meta.ai/docs/file-handling ("Send a file inline", read 4 Nov 2026), in a chat
 * completion: `{"type":"file","file":{"filename":"x.pdf","file_data":"data:application/pdf;base64,..."}}`.
 * Meta takes up to 50 MB inline; base64 adds a third, so the app stops at [MAX_BYTES].
 */
object ChatDocuments {

  private const val DIR = "chat_docs"
  const val PDF_MIME = "application/pdf"

  /** A phone-sized document; well under Meta's 50 MB inline limit once base64 adds its third. */
  const val MAX_BYTES = 20L * 1024 * 1024

  fun isDocument(path: String?): Boolean = path != null && path.endsWith(".pdf", ignoreCase = true)

  /** The name shown in the chat and sent to Meta. */
  fun displayName(path: String): String = File(path).name

  /** Whether [uri] points at a PDF, by the type its provider reports. */
  fun isPdf(context: Context, uri: Uri): Boolean = context.contentResolver.getType(uri) == PDF_MIME

  sealed interface Imported {
    data class Ok(val path: String) : Imported
    data object TooLarge : Imported
    data object Unreadable : Imported
  }

  /** Copies the PDF at [uri] into the app's files under its own name. Blocking: call off Main. */
  fun import(context: Context, uri: Uri): Imported = runCatching {
    val resolver = context.contentResolver
    var name = "document.pdf"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
      if (c.moveToFirst()) {
        c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
        if (!c.isNull(1)) size = c.getLong(1)
      }
    }
    if (size > MAX_BYTES) return@runCatching Imported.TooLarge
    val safe = name.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").take(120).let {
      if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
    }
    val dir = File(File(context.filesDir, DIR), UUID.randomUUID().toString()).apply { mkdirs() }
    val target = File(dir, safe)
    val copied = resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
      ?: return@runCatching Imported.Unreadable
    if (copied > MAX_BYTES) {
      dir.deleteRecursively()
      return@runCatching Imported.TooLarge
    }
    Imported.Ok(target.absolutePath)
  }.getOrDefault(Imported.Unreadable)

  /** The PDF at [path] as a `data:application/pdf;base64,…` URL for `file_data`, or null if the file is gone. */
  fun dataUrl(path: String): String? {
    val file = File(path)
    if (!file.isFile) return null
    return "data:$PDF_MIME;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
  }
}
