package com.aiopenpocket.agent.loop

// @trace fonovye-hody-agenta-dde42d

/**
 * Накопитель стрим-текста с гейтом частоты публикаций: toString() копирует весь накопленный текст,
 * чаще ~20 раз/сек глазу не нужно. Часы инъектируются (монотонные, см. TurnModule) — класс чистый
 * и тестируется без Android.
 */
class StreamingTextBuffer(
  private val nowMs: () -> Long,
  private val minPublishIntervalMs: Long = DEFAULT_PUBLISH_INTERVAL_MS,
) {
  private val builder = StringBuilder()
  private var lastPublishAt = 0L

  /** Копит [delta]; возвращает снапшот для публикации, если гейт пропускает, иначе null. */
  fun append(delta: String): String? {
    builder.append(delta)
    val now = nowMs()
    if (now - lastPublishAt < minPublishIntervalMs) return null
    lastPublishAt = now
    return builder.toString()
  }

  /** Хвост стрима, не прошедший гейт: снапшот, если есть накопленное, иначе null. */
  fun flush(): String? = if (builder.isNotEmpty()) builder.toString() else null

  fun reset() {
    builder.setLength(0)
    lastPublishAt = 0L
  }

  companion object {
    /** Минимальный интервал публикации стрим-текста (~20 обновлений/сек). */
    const val DEFAULT_PUBLISH_INTERVAL_MS = 50L
  }
}
