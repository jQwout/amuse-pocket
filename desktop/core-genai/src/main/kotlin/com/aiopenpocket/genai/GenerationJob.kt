package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.Serializable

@Serializable
enum class GenerationModality(val slug: String, val label: String) {
  IMAGE("image", "Картинка"),
  VIDEO("video", "Видео"),
  AUDIO("audio", "Аудио"),
}

/**
 * Роль вложения.
 *
 * Честность про API: у **видео** роли настоящие — `frame_images` с `frame_type` для первого и
 * последнего кадра, `input_references` для остального. У **картинок** структуры ролей в API нет
 * вовсе, там роль и теги уходят словами в промпт, и UI обязан говорить об этом прямо, а не делать
 * вид, что провайдер их понимает.
 */
@Serializable
enum class AttachmentRole(val label: String, val forVideoOnly: Boolean = false) {
  BACKGROUND("Фон"),
  CHARACTER("Персонаж"),
  STYLE("Стиль"),
  REFERENCE("Референс"),
  FIRST_FRAME("Первый кадр", forVideoOnly = true),
  LAST_FRAME("Последний кадр", forVideoOnly = true),
}

/**
 * Входной файл: путь на диске или публичный URL.
 *
 * Здесь сознательно НЕ data-URL — по той же причине, что и раньше: запрос лежит в истории на диске,
 * которая переписывается на каждом переходе статуса, и картинка на 6 МБ превратилась бы в 8 МБ
 * base64, переписываемых по пять раз за генерацию.
 */
@Serializable
data class InputAttachment(
  val path: String,
  val role: AttachmentRole = AttachmentRole.REFERENCE,
  val tags: List<String> = emptyList(),
) {
  val name: String get() = path.substringAfterLast('/').substringAfterLast('\\')
}

@Serializable
data class GenerationRequest(
  val modality: GenerationModality,
  val prompt: String,
  val model: String,
  /**
   * Входная картинка: путь к файлу на диске **или** публичный URL. image-to-image для
   * [GenerationModality.IMAGE], image-to-video для [GenerationModality.VIDEO].
   *
   * Здесь сознательно НЕ data-URL: запрос лежит внутри задания, а задание — в истории на диске,
   * которая переписывается на каждом переходе статуса. Картинка на 6 МБ превратилась бы в 8 МБ
   * base64 и переписывалась бы по пять раз за одну генерацию. В `data:` путь разворачивается
   * в момент запроса и в историю не попадает.
   */
  val inputImage: String? = null,
  /** Голос для TTS; у остальных модальностей игнорируется. */
  val voice: String? = null,

  /** Файлы на входе: несколько, каждый со своей ролью. */
  val attachments: List<InputAttachment> = emptyList(),

  /**
   * Параметры видео. null означает «не задавать» — и это не то же самое, что «по умолчанию»:
   * модели принимают разные наборы длительностей и разрешений (см. [VideoCapabilities]), и слать
   * им значение, которого они не заявляли, значит получать отказ на ровном месте.
   */
  val durationSeconds: Int? = null,
  /** Тир разрешения. У видео это `720p`/`1080p`, у картинки — `1K`/`2K`: списки задаёт сама модель. */
  val resolution: String? = null,
  val aspectRatio: String? = null,
  /** Формат файла на выходе (`png`/`jpeg`/`webp`). Только картинки и только там, где заявлен. */
  val outputFormat: String? = null,
  /** Уровень качества (`auto`/`low`/`medium`/`high`) — там, где модель его заявила. */
  val quality: String? = null,
)

@Serializable
enum class JobStatus {
  QUEUED,
  RUNNING,

  /**
   * Задание принято провайдером и ждёт результата на его стороне (видео — асинхронное).
   * Отдельный статус от [RUNNING], потому что переживает перезапуск приложения:
   * по сохранённому `pollingUrl` поллинг продолжается с нуля процесса.
   */
  POLLING,
  DONE,
  FAILED,
  CANCELLED,
  ;

  val isTerminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED
}

/**
 * Оценка результата пользователем.
 *
 * Хранится в задании, а не в отдельном файле: оценка бессмысленна в отрыве от промпта, модели и
 * параметров, которыми кадр сделан, — а всё это уже лежит здесь.
 */
@Serializable
enum class JobRating { NONE, LIKED, DISLIKED }

@Serializable
data class GenerationJob(
  val id: String,
  val request: GenerationRequest,
  val createdAtEpochMs: Long,
  val status: JobStatus = JobStatus.QUEUED,
  /** id задания у провайдера — только для асинхронных модальностей. */
  val providerJobId: String? = null,
  /** URL для догоняющего поллинга. Хранится в БД, а не в памяти: см. [JobStatus.POLLING]. */
  val pollingUrl: String? = null,
  /** Человекочитаемая нота о том, что происходит прямо сейчас («ждём провайдера, попытка 3»). */
  val note: String? = null,
  /**
   * Общий ключ пачки: несколько вариантов одного промпта или один промпт по нескольким моделям.
   * null — одиночное задание. Нужен, чтобы история показывала результаты рядом, а не вперемешку.
   */
  val batchId: String? = null,
  /** Пути готовых файлов на диске. */
  val artifacts: List<String> = emptyList(),
  val costUsd: Double? = null,
  val error: String? = null,
  /** Понравился кадр или нет. Значение по умолчанию делает старые `jobs.json` читаемыми как есть. */
  val rating: JobRating = JobRating.NONE,
)
