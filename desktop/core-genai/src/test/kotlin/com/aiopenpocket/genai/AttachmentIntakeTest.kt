package com.aiopenpocket.genai

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Сторож входа во вложения. Проверка тут не косметическая: файл, прошедший её насквозь, уезжает
 * в платный запрос как картинка (см. `AttachmentIntake`).
 */
class AttachmentIntakeTest {

  private fun tempFile(name: String, bytes: Int = 8): File =
    File.createTempFile("intake-", "-$name").apply {
      writeBytes(ByteArray(bytes))
      deleteOnExit()
    }

  @Test
  fun `image extensions pass`() {
    for (ext in listOf("png", "jpg", "jpeg", "webp", "gif")) {
      val file = tempFile("x.$ext")
      assertNull(rejectionReason(file), "$ext должен приниматься, а отказ был: ${rejectionReason(file)}")
    }
  }

  @Test
  fun `extension check is case insensitive`() {
    assertNull(rejectionReason(tempFile("x.PNG")))
    assertNull(rejectionReason(tempFile("x.JPeG")))
  }

  @Test
  fun `non-image is rejected before it can be billed`() {
    // Ровно тот случай, ради которого сторож и заведён: mimeByExtension отдал бы
    // application/octet-stream, и это ушло бы в chat/completions как image_url.
    val reason = rejectionReason(tempFile("clip.mp4"))
    assertNotNull(reason)
    assertTrue("формат" in reason, reason)

    assertNotNull(rejectionReason(tempFile("notes.txt")))
    assertNotNull(rejectionReason(tempFile("noextension")))
  }

  @Test
  fun `directory is rejected`() {
    val dir = File(System.getProperty("java.io.tmpdir"), "intake-dir-test").apply { mkdirs() }
    try {
      assertEquals("это папка, а не файл", rejectionReason(dir))
    } finally {
      dir.delete()
    }
  }

  @Test
  fun `missing file is rejected`() {
    assertEquals("файл не найден", rejectionReason(File("нет-такого-файла.png")))
  }

  @Test
  fun `oversized file is rejected before readBytes can throw an Error`() {
    // readBytes() на слишком большом файле кидает OutOfMemoryError — это Error, он проходит мимо
    // catch(Exception) в очереди, и задание вместо FAILED навсегда виснет в RUNNING.
    val file = tempFile("big.png", bytes = 64)
    val reason = rejectionReason(file, maxBytes = 32)
    assertNotNull(reason)
    assertTrue("больше" in reason, reason)

    assertNull(rejectionReason(file, maxBytes = 64), "ровно по границе — ещё можно")
  }

  @Test
  fun `supported extensions match the mime table`() {
    // Два списка обязаны совпадать: «формат, для которого нет mime» и есть «формат, который нельзя
    // брать». Разъедутся — и мусор снова поедет в запрос под видом octet-stream.
    for (ext in SUPPORTED_EXTENSIONS) {
      assertTrue(
        mimeByExtension("f.$ext") != FALLBACK_MIME,
        "$ext в списке разрешённых, но mime для него нет",
      )
    }
  }
}
