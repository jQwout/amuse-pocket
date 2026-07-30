package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * Приводит [GenerationRequest.inputImage] к тому, что понимает провайдер: ссылку оставляет как
 * есть, локальный путь читает с диска в `data:`-URL.
 *
 * Чтение уходит на IO-диспетчер: файл бывает в десятки мегабайт, и делать это в потоке, который
 * гоняет очередь (или, того хуже, в обработчике клика), означало бы подвешивать приложение.
 */
suspend fun resolveInputImage(reference: String): String =
  if (reference.startsWith("http://") || reference.startsWith("https://") || reference.startsWith("data:")) {
    reference
  } else {
    withContext(Dispatchers.IO) { imageFileToDataUrl(Path.of(reference)) }
  }

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
