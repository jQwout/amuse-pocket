package com.aiopenpocket.genai

// @trace desktop-genai-client-4c63b6

import kotlinx.coroutines.flow.Flow

/** Готовый результат: либо байты в ответе (картинка/аудио), либо ссылка на скачивание (видео). */
sealed interface GenerationOutput {
  val mimeType: String

  data class Bytes(val data: ByteArray, override val mimeType: String) : GenerationOutput {
    // data class с ByteArray сравнивался бы по ссылке — переопределяем, чтобы тесты и дедупликация
    // сравнивали содержимое.
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Bytes) return false
      return mimeType == other.mimeType && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * data.contentHashCode() + mimeType.hashCode()
  }

  data class RemoteUrl(val url: String, override val mimeType: String) : GenerationOutput
}

data class GenerationResult(
  val outputs: List<GenerationOutput>,
  val costUsd: Double? = null,
  val modelUsed: String? = null,
)

/**
 * Ход одной генерации. Синхронные модальности эмитят [Accepted] → [Completed],
 * асинхронные добавляют между ними серию [Status] на каждый опрос провайдера.
 * Сделано потоком (а не suspend-функцией с результатом), чтобы очередь показывала прогресс
 * длинного видео-задания, а не молчала минуту.
 */
sealed interface GenerationProgress {
  data class Accepted(val providerJobId: String? = null, val pollingUrl: String? = null) : GenerationProgress
  data class Status(val note: String) : GenerationProgress
  data class Completed(val result: GenerationResult) : GenerationProgress
  data class Failed(val message: String, val code: String? = null) : GenerationProgress
}

interface GenerationProvider {
  val id: String

  fun supports(modality: GenerationModality): Boolean

  fun generate(request: GenerationRequest): Flow<GenerationProgress>

  /**
   * Догоняет задание, отправленное в прошлом запуске приложения. Без этого асинхронное видео
   * терялось бы при закрытии окна: задание у провайдера считается, а забрать его уже нечем.
   */
  fun resume(pollingUrl: String): Flow<GenerationProgress>
}
