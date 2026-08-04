package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File

/**
 * Каталог моделей по модальностям — ровно та же роль, что у `assets/config/models.json` в :app:
 * единственный источник правды о том, какие модели предлагать, правится руками без перекомпиляции.
 *
 * Слаги моделей у OpenRouter меняются быстро, поэтому UI позволяет ввести модель руками, а каталог
 * даёт лишь подсказки: устаревшая запись здесь не должна блокировать генерацию.
 */
@Serializable
data class GenaiModelCatalog(
  val models: List<GenaiModelEntry> = emptyList(),
) {
  fun forModality(modality: GenerationModality): List<GenaiModelEntry> =
    models.filter { it.modality == modality }.sortedBy { it.priority }

  fun defaultFor(modality: GenerationModality): GenaiModelEntry? = forModality(modality).firstOrNull()

  companion object {
    private const val RESOURCE_PATH = "/genai/models.json"

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
      ignoreUnknownKeys = true
      isLenient = true
      namingStrategy = JsonNamingStrategy.SnakeCase
    }

    /**
     * Читает каталог из [override] (если файл есть и разбирается), иначе из ресурса в jar.
     * Пользовательский файл имеет приоритет: так новые модели добавляются без пересборки.
     *
     * Порядок отступления важен: опечатка в своём файле роняет каталог до ВСТРОЕННОГО списка,
     * а не до трёх аварийных моделей. Раньше было наоборот — одна лишняя запятая, и половина
     * подсказок молча пропадала.
     */
    fun load(override: File? = null): GenaiModelCatalog {
      val user = override?.let(::readCatalog)
      if (user != null && user.models.isNotEmpty()) return user
      val builtIn = readResource()
      return builtIn.takeIf { it.models.isNotEmpty() } ?: FALLBACK
    }

    private fun readCatalog(file: File): GenaiModelCatalog? {
      if (!file.isFile) return null
      val text = runCatching { file.readText() }.getOrNull() ?: return null
      return runCatching { json.decodeFromString<GenaiModelCatalog>(text) }.getOrNull()
    }

    private fun readResource(): GenaiModelCatalog {
      val text = GenaiModelCatalog::class.java.getResourceAsStream(RESOURCE_PATH)
        ?.bufferedReader()?.use { it.readText() } ?: return FALLBACK
      return runCatching { json.decodeFromString<GenaiModelCatalog>(text) }.getOrDefault(FALLBACK)
    }

    /** Хоть по одной модели на модальность — иначе экран генерации оказался бы без выбора вообще. */
    val FALLBACK = GenaiModelCatalog(
      models = listOf(
        GenaiModelEntry("black-forest-labs/flux.2-pro", GenerationModality.IMAGE, priority = 1),
        GenaiModelEntry("google/veo-3.1-fast", GenerationModality.VIDEO, priority = 1),
        GenaiModelEntry("openai/gpt-4o-mini-tts", GenerationModality.AUDIO, priority = 1),
      ),
    )
  }
}

@Serializable
data class GenaiModelEntry(
  /** Слаг модели у OpenRouter, например `black-forest-labs/flux.2-pro`. */
  val id: String,
  val modality: GenerationModality,
  /** Порядок в списке: 1 — предлагается по умолчанию. */
  val priority: Int = Int.MAX_VALUE,
  /** Короткая подпись для UI; пусто — показываем сам слаг. */
  val label: String = "",
  /** Голоса, если модель их поддерживает (TTS). */
  val voices: List<String> = emptyList(),
) {
  val displayName: String get() = label.ifBlank { id }
}
