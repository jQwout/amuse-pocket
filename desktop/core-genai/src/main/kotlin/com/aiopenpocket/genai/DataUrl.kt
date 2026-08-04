package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

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
    withContext(Dispatchers.IO) { imageFileToDataUrl(File(reference)) }
  }

/**
 * Локальный файл → `data:`-URL. Нужно для image-to-image и image-to-video: OpenRouter принимает
 * либо публичную ссылку, либо base64, а у картинки с диска пользователя публичной ссылки нет.
 */
@OptIn(ExperimentalEncodingApi::class)
fun imageFileToDataUrl(file: File): String {
  val bytes = file.readBytes()
  val mime = mimeByExtension(file.name)
  // Переводы строк в base64 недопустимы — только одна непрерывная строка.
  return "data:$mime;base64," + Base64.encode(bytes)
}

internal fun mimeByExtension(fileName: String): String =
  when (fileName.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> "application/octet-stream"
  }
