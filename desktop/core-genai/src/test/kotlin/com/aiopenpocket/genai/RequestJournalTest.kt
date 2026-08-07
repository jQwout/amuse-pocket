package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Журнал — единственное место, где приложение может проговориться: он держит тела запросов, а в них
 * и ключ в заголовке, и картинки пользователя целиком. Поэтому чистка тел проверяется здесь, а не
 * доверяется экрану.
 */
class RequestJournalTest {

  private fun entry(id: String = "1", body: String = "{}") = JournalEntry(
    id = id,
    atEpochMs = 1,
    modality = GenerationModality.IMAGE,
    model = "vendor/model",
    method = "POST",
    url = "https://openrouter.ai/api/v1/images",
    requestBody = body,
  )

  @Test
  fun `the key never survives its way into the journal`() {
    val text = """{"h":"Bearer sk-or-v1-3f9a8b7c6d5e4f3a2b1c0d9e8f7a6b5c","model":"x"}"""

    val safe = journalSafe(text)

    assertFalse(safe.contains("sk-or-v1"), "ключ остался в журнале: $safe")
    assertTrue(safe.contains("Bearer ***"))
    assertTrue(safe.contains("\"model\":\"x\""), "вычистили лишнее")
  }

  @Test
  fun `base64 payloads are folded down to their size`() {
    // Картинка на 6 МБ превращается в 8 МБ base64 прямо в теле запроса: держать это в памяти
    // двумястами записей нельзя, а читать — тем более.
    val payload = "A".repeat(4000)
    val text = """{"image_url":{"url":"data:image/png;base64,$payload"},"prompt":"кот"}"""

    val safe = journalSafe(text)

    assertFalse(safe.contains(payload), "base64 не свернулся")
    assertTrue(safe.contains("data:image/png;base64, 2 КБ свёрнуто"), safe)
    // Свернули вложение, а не весь запрос: промпт — то, ради чего в журнал и смотрят.
    assertTrue(safe.contains("\"prompt\":\"кот\""), safe)
  }

  @Test
  fun `folding stops at the end of the value, not at the end of the body`() {
    val text = """{"a":"data:image/png;base64,QUJD","b":"после"}"""

    val safe = journalSafe(text)

    assertTrue(safe.contains("\"b\":\"после\""), "свёртка съела соседнее поле: $safe")
  }

  @Test
  fun `a disabled journal keeps nothing at all`() {
        val journal = InMemoryRequestJournal()

    journal.record(entry())

    assertEquals(emptyList(), journal.entries.value, "выключенный журнал всё-таки копил записи")
  }

  @Test
  fun `switching off wipes what was already collected`() {
    // «Выключено» обязано значить «ничего не храним», а не «не показываем».
    val journal = InMemoryRequestJournal().apply { enabled = true }
    journal.record(entry())
    assertEquals(1, journal.entries.value.size)

    journal.enabled = false

    assertEquals(emptyList(), journal.entries.value)
  }

  @Test
  fun `newest entries come first and the ring evicts the oldest`() {
    val journal = InMemoryRequestJournal(limit = 3).apply { enabled = true }

    repeat(5) { index -> journal.record(entry(id = index.toString())) }

    // Журнал открывают ради последнего запроса, а не первого.
    assertEquals(listOf("4", "3", "2"), journal.entries.value.map { it.id })
  }

  @Test
  fun `an off journal accepts writes and stays empty`() {
    RequestJournal.Off.record(entry())

    assertEquals(emptyList(), RequestJournal.Off.entries.value)
  }

  @Test
  fun `a non-2xx answer is marked as failed`() {
    assertTrue(entry().copy(status = 429).failed)
    assertTrue(entry().copy(status = 200, error = "сеть отвалилась").failed)
    assertFalse(entry().copy(status = 200).failed)
  }

  @Test
  fun `json is laid out for reading, and anything else survives untouched`() {
    val laid = prettyJson("""{"model":"x","prompt":"кот"}""")

    assertTrue(laid.lines().size > 1, "тело осталось одной строкой: $laid")
    assertTrue(laid.contains("\"prompt\": \"кот\""), laid)

    // Не-JSON — тоже содержимое журнала: отметка про бинарный ответ, пустота, обрывок ошибки.
    // Подменять их сообщением о неудачном разборе значит потерять единственное, что сказал провайдер.
    assertEquals("бинарный ответ, 340 КБ", prettyJson("бинарный ответ, 340 КБ"))
    assertEquals("", prettyJson(""))
    assertEquals("502 Bad Gateway <html>", prettyJson("502 Bad Gateway <html>"))
  }

  @Test
  fun `sizes are spoken in human units`() {
    assertEquals("512 Б", humanBytes(512))
    assertEquals("340 КБ", humanBytes(340 * 1024L))
    assertEquals("2.4 МБ", humanBytes((2.4 * 1024 * 1024).toLong()))
  }
}
