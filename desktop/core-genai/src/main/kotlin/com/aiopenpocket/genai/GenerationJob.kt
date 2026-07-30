package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.serialization.Serializable

@Serializable
enum class GenerationModality(val slug: String, val label: String) {
  IMAGE("image", "Картинка"),
  VIDEO("video", "Видео"),
  AUDIO("audio", "Аудио"),
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
)
