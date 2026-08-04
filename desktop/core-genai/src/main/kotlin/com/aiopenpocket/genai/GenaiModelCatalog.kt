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

    /**
     * Только пользовательский файл, без встроенного списка. Пустой каталог — файла нет или он битый.
     *
     * Нужен отдельно от [load], потому что «список пользователя» и «подсказки на крайний случай» —
     * разные вещи: первое живой каталог отменить не вправе, второе он замещает.
     */
    fun loadOverride(file: File?): GenaiModelCatalog =
      file?.let(::readCatalog) ?: GenaiModelCatalog()

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
  /** Что умеет видео-модель. null у всех остальных модальностей. */
  val video: VideoCapabilities? = null,
) {
  /**
   * Подпись для UI. Своя подпись побеждает: она стоит в `genai-models.json` руками, то есть это
   * указание пользователя, а не догадка. Пусто — выводим имя из слага: живой каталог подписей не
   * приносит вовсе, и без этого список моделей превращается в стену одинаковых строк.
   */
  val displayName: String get() = label.ifBlank { humanModelName(id) }

  /** Вендор для второй строки в списке: `google`, `openai`, … Пусто, если слаг без вендора. */
  val vendor: String get() = modelVendor(id)
}

/**
 * Что видео-модель принимает — по её собственным словам из `/api/v1/videos/models`.
 *
 * Списки пустые, а не «значения по умолчанию»: у части моделей поля приходят `null`, и подставить
 * туда «720p, 5 секунд» значило бы предложить пользователю то, чего провайдер не обещал. Пустой
 * список для UI — команда не рисовать контрол вовсе, а не рисовать его пустым.
 */
@Serializable
data class VideoCapabilities(
  /** Допустимые длительности в секундах. Именно список, а не диапазон: модели принимают не всё подряд. */
  val durations: List<Int> = emptyList(),
  val resolutions: List<String> = emptyList(),
  val aspectRatios: List<String> = emptyList(),
  val supportsFirstFrame: Boolean = false,
  val supportsLastFrame: Boolean = false,
  val generatesAudio: Boolean = false,
  val supportsSeed: Boolean = false,
) {
  val hasAnyControl: Boolean
    get() = durations.isNotEmpty() || resolutions.isNotEmpty() || aspectRatios.isNotEmpty()
}

/** Вендор из слага (`google/gemini-3-pro-image` → `google`). Пусто, если разделителя нет. */
fun modelVendor(id: String): String = if ('/' in id) id.substringBefore('/') else ""

/**
 * Человекочитаемое имя из слага: `google/gemini-3.1-flash-lite-image` → `Gemini 3.1 Flash Lite Image`.
 *
 * Нужно потому, что живой `/api/v1/models` отдаёт только идентификаторы, а отличить на глаз
 * `gemini-3.1-flash-image` от `gemini-3.1-flash-image-preview` в списке из одиннадцати строк
 * невозможно. Правила простые и без словаря моделей: словарь устарел бы ровно так же, как
 * устаревал вшитый список.
 */
fun humanModelName(id: String): String {
  val tail = id.substringAfterLast('/')
  if (tail.isBlank()) return id
  return tail.split('-', '_')
    .filter { it.isNotBlank() }
    .joinToString(" ") { word ->
      when {
        // gpt, tts, ai — читаются как аббревиатуры, «Gpt 5» выглядит опечаткой.
        word.lowercase() in ACRONYMS -> word.uppercase()
        // Токены с цифрами оставляем в верхнем регистре целиком: «82m» → «82M», «3.1» → «3.1».
        word.any { it.isDigit() } -> word.uppercase()
        else -> word.replaceFirstChar { it.uppercase() }
      }
    }
}

private val ACRONYMS = setOf("gpt", "tts", "ai", "hd", "xl", "sd", "api")
