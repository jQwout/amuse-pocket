package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readBytes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Кладёт результаты генерации на диск и возвращает пути. Видео приходит ссылкой, а не байтами,
 * поэтому качать умеет тоже здесь — с ключом в заголовке: content-ссылки OpenRouter требуют авторизации.
 */
class ArtifactStore(
  engine: HttpClientEngine,
  private val apiKey: () -> String,
  private val paths: AppPaths = AppPaths(),
) {

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = 15_000
      // Видео весит десятки мегабайт — на скачивание даём заметно больше, чем на API-запрос.
      requestTimeoutMillis = 600_000
      socketTimeoutMillis = 600_000
    }
  }

  /** Возвращает пути сохранённых файлов в порядке [outputs]. */
  suspend fun save(jobId: String, outputs: List<GenerationOutput>): List<Path> {
    val dir = paths.outputDir.resolve(jobId)
    dir.createDirectories()
    return outputs.mapIndexed { index, output ->
      val bytes = when (output) {
        is GenerationOutput.Bytes -> output.data
        is GenerationOutput.RemoteUrl -> download(output.url)
      }
      val file = dir.resolve("%02d.%s".format(index + 1, extensionFor(output.mimeType)))
      Files.write(file, bytes)
      file
    }
  }

  private suspend fun download(url: String): ByteArray {
    val response = httpClient.get(url) {
      val key = apiKey()
      if (key.isNotBlank()) header("Authorization", "Bearer $key")
    }
    val code = response.status.value
    if (code !in 200..299) throw OpenRouterHttpException(code, "не удалось скачать $url")
    return response.readBytes()
  }

  private fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').trim()) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/pcm" -> "pcm"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    else -> "bin"
  }
}
