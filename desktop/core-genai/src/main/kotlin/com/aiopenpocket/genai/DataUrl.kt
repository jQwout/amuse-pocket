package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Локальный файл → `data:`-URL. Нужно для image-to-image и image-to-video: OpenRouter принимает
 * либо публичную ссылку, либо base64, а у картинки с диска пользователя публичной ссылки нет.
 */
fun imageFileToDataUrl(path: Path): String {
  val bytes = Files.readAllBytes(path)
  val mime = mimeByExtension(path.fileName.toString())
  // Переводы строк в base64 недопустимы — только одна непрерывная строка.
  return "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)
}

internal fun mimeByExtension(fileName: String): String =
  when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
  }
