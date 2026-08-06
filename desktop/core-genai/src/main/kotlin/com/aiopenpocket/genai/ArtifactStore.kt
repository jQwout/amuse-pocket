package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readBytes
import java.io.File
import java.util.Locale

/**
 * Кладёт результаты генерации на диск и возвращает пути. Видео приходит ссылкой, а не байтами,
 * поэтому качать умеет тоже здесь — с ключом в заголовке: content-ссылки OpenRouter требуют авторизации.
 */
class ArtifactStore(
  engine: HttpClientEngine,
  private val apiKey: () -> String,
  private val paths: AppPaths = AppPaths(),
) : AutoCloseable {

  private val httpClient = HttpClient(engine) {
    install(HttpTimeout) {
      connectTimeoutMillis = 15_000
      // Видео весит десятки мегабайт — на скачивание даём заметно больше, чем на API-запрос.
      requestTimeoutMillis = 600_000
      socketTimeoutMillis = 600_000
    }
  }

  override fun close() = httpClient.close()

  /** Возвращает сохранённые файлы в порядке [outputs]. */
  suspend fun save(jobId: String, outputs: List<GenerationOutput>): List<File> {
    val dir = dirFor(jobId)
    dir.mkdirs()
    return outputs.mapIndexed { index, output ->
      val bytes = when (output) {
        is GenerationOutput.Bytes -> output.data
        is GenerationOutput.RemoteUrl -> download(output.url)
      }
      // Locale.ROOT: в некоторых локалях %d даёт не ASCII-цифры, а имена файлов не место
      // для сюрпризов.
      val file = File(dir, "%02d.%s".format(Locale.ROOT, index + 1, extensionFor(output.mimeType)))
      file.writeBytes(bytes)
      file
    }
  }

  /**
   * Ключ уходит только на сам OpenRouter — см. [isOpenRouterHost]. Ссылку на результат выбирает не
   * пользователь и не мы, а JSON провайдера, и слать `Bearer` на любой названный там хост значит
   * отдать ключ тому, кого никто не выбирал.
   */
  private suspend fun download(url: String): ByteArray {
    val response = httpClient.get(url) {
      val key = apiKey()
      if (key.isNotBlank() && isOpenRouterHost(url)) header("Authorization", "Bearer $key")
    }
    val code = response.status.value
    if (code !in 200..299) throw OpenRouterHttpException(code, "не удалось скачать $url")
    return response.readBytes()
  }

  /**
   * Каталог, в который задание пишет свои файлы. Нужен снаружи: удаление сносит его целиком, потому
   * что список путей в задании появляется только после успешной записи, а прерванное на середине
   * задание оставляет файлы уже на диске.
   */
  fun dirFor(jobId: String): File = File(paths.outputDir, safeDirName(jobId))

  /**
   * Имя каталога из id задания. Свои id — безопасный UUID, но история лежит в правимом руками
   * `jobs.json`, и `..` в id уводил бы запись за пределы папки результатов.
   */
  private fun safeDirName(jobId: String): String =
    jobId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifBlank { "job" }

  private fun extensionFor(mimeType: String): String = when (mimeType.substringBefore(';').trim()) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpg"
    "image/webp" -> "webp"
    // Ручка картинок умеет отдавать SVG (см. ImageDataDto.mediaType) — без этой строки он ложился
    // на диск как .bin и в карточке рисовался словом «Картинка».
    "image/svg+xml" -> "svg"
    "audio/mpeg", "audio/mp3" -> "mp3"
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/pcm" -> "pcm"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    else -> "bin"
  }
}
