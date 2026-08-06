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
  /**
   * Имя от вендора из живого каталога («Google: Nano Banana 2»). Пусто у записей из сида и у своих
   * — там его взять неоткуда.
   */
  val vendorName: String = "",
  /**
   * Описание модели из живого каталога. В списке выбора это единственное, чем один
   * `gemini-*-image` отличается от соседнего: по слагам они неразличимы.
   */
  val description: String = "",
  /** Голоса, если модель их поддерживает (TTS). */
  val voices: List<String> = emptyList(),
  /**
   * Unix-время появления модели у провайдера. Нужно ради сортировки «Новые» в окне выбора: раньше
   * оно использовалось при сборке каталога и выбрасывалось, а порядок «новые выше» после склейки
   * с пользовательским списком и сидом уже не восстановить.
   */
  val created: Long = 0,
  /**
   * Что провайдер берёт за ВЫВОД — в той единице, в какой назвал сам. Пусто — прайса нет.
   * См. [ImagePrice]: складывать разные единицы в одно число нельзя.
   */
  val pricing: List<ImagePrice> = emptyList(),
  /** Что умеет видео-модель. null у всех остальных модальностей. */
  val video: VideoCapabilities? = null,
  /** Что умеет картиночная модель. null у всех остальных модальностей. */
  val image: ImageCapabilities? = null,
) {
  /**
   * Подпись для UI, по убыванию доверия: своя из `genai-models.json` (прямое указание пользователя),
   * потом имя от вендора из живого каталога, и только потом догадка по слагу.
   *
   * Порядок именно такой, потому что догадка по слагу — худший из трёх источников, а стояла она
   * вторым: из `gemini-3.1-flash-image` никакими правилами не вывести «Nano Banana 2», а вендор это
   * имя присылает сам. Раньше `name` из ответа просто выбрасывался.
   */
  val displayName: String get() = label.ifBlank { vendorName.ifBlank { humanModelName(id) } }

  /** Вендор для второй строки в списке: `google`, `openai`, … Пусто, если слаг без вендора. */
  val vendor: String get() = modelVendor(id)
}

/**
 * Одна строка прайса на ВЫВОД модели — ровно в той единице, в какой её назвал провайдер.
 *
 * Единица здесь не подробность, а главное. `/api/v1/images/models/{id}/endpoints` отдаёт три разных
 * вида, и свести их к одному числу нельзя (проверено на живом API 2026-08-06):
 *
 * - `image` — цена за кадр, готовая: `seedream-4.5` = $0.04, `riverflow-v2.5-pro` = $0.13/$0.15/$0.17
 *   по тирам;
 * - `megapixel` — за мегапиксель: `flux.2-pro` = $0.03. Сколько мегапикселей выйдет, знает только
 *   провайдер: «1K» — это тир, а не размер, и итог зависит ещё и от соотношения сторон;
 * - `token` — за токен: `gemini-3-pro-image` = $0.00012. Сколько токенов стоит кадр, каталог не
 *   публикует вовсе — это ровно то, о чём предупреждает `costEstimate`.
 *
 * Поэтому здесь хранится сырая пара «единица + цена», а превращать её в подпись — работа
 * [ModelPrice], где каждый случай назван вслух. Умножить ставку за мегапиксель на выдуманное число
 * мегапикселей и показать это ценой за кадр значило бы соврать про деньги с видом точного расчёта.
 *
 * Берём только `output_image`. Вход (`input_image`, `input_text`) провайдер считает отдельно, и
 * сложить их в одну цифру честно нельзя: она зависит от длины промпта и числа референсов, то есть
 * от того, что ещё не набрано.
 */
@Serializable
data class ImagePrice(
  /** `image`, `megapixel`, `token` — что именно считают. */
  val unit: String,
  val costUsd: Double,
  /** Тир, к которому относится цена (`1k`, `2k`, `4k`). Пусто — цена одна на все. */
  val variant: String = "",
)

/**
 * Что картиночная модель принимает — по её собственным словам из `/api/v1/images/models`.
 *
 * Ровно та же логика, что у [VideoCapabilities], и по той же причине: наборы у моделей разные.
 * Разрешение заявляют 16 моделей из 40, соотношение сторон — 37, формат файла — 6. Показать
 * «2K» там, где модель о нём не говорила, значит предложить значение, на котором запрос вернётся
 * отказом уже после нажатия «Сгенерировать», то есть за деньги.
 *
 * Пустой список — команда UI не рисовать контрол вовсе, а не рисовать его пустым.
 */
@Serializable
data class ImageCapabilities(
  /** Тиры вроде `1K`, `2K` — не пиксели. */
  val resolutions: List<String> = emptyList(),
  val aspectRatios: List<String> = emptyList(),
  /** `png`, `jpeg`, `webp` — у кого заявлено. */
  val outputFormats: List<String> = emptyList(),
  /** `auto`, `low`, `medium`, `high` — заявляют немногие. */
  val qualities: List<String> = emptyList(),
  /**
   * Сколько входных картинок модель берёт разом (`input_references.max`). 0 — не заявлено.
   *
   * Разброс огромный и он решает, годится ли модель под уже прикреплённые файлы: у `krea-2-large`
   * это 1, у `gemini-3-pro-image` — 14, у `gpt-image-2` — 16 (проверено на живом API 2026-08-06).
   * Прислать модели шесть референсов, когда она берёт один, значит получить отказ за деньги.
   */
  val maxReferences: Int = 0,
) {
  /** Есть ли что показать в блоке «Формат сторон». */
  val hasAspect: Boolean get() = aspectRatios.isNotEmpty()

  /** Есть ли что показать в блоке «Качество»: тир, уровень качества или формат файла. */
  val hasQuality: Boolean
    get() = resolutions.isNotEmpty() || qualities.isNotEmpty() || outputFormats.isNotEmpty()

  val hasAnyControl: Boolean get() = hasAspect || hasQuality
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
 * Нужно потому, что живой `/api/v1/models` подписей не приносит для моделей из сида, а у живых записей имя вендора берётся из поля name, а отличить на глаз
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
