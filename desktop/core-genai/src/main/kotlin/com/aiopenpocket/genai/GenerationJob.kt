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
   * Входная картинка как data-URL (`data:image/png;base64,…`) или публичный URL —
   * image-to-image для [GenerationModality.IMAGE] и image-to-video для [GenerationModality.VIDEO].
   */
  val inputImageUrl: String? = null,
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
  /** Пути готовых файлов на диске. */
  val artifacts: List<String> = emptyList(),
  val costUsd: Double? = null,
  val error: String? = null,
)
