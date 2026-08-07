package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Приписка про исходники — единственное, чем пользователь может сослаться на конкретную картинку.
 *
 * Поля для имени или подписи у элемента `input_references` в API нет: модели заявляют его как
 * диапазон, то есть «сколько картинок можно прислать». Модель видит байты в порядке массива и
 * больше ничего, поэтому номер в тексте обязан совпадать с позицией в массиве — иначе промпт
 * указывает не на ту картинку, и по ответу это не поймать.
 */
class DescribeAttachmentsTest {

  private fun file(name: String, role: AttachmentRole = AttachmentRole.CHARACTER, tags: List<String> = emptyList()) =
    InputAttachment(path = "/x/$name", role = role, tags = tags)

  @Test
  fun `numbers follow the position in the request, with nothing skipped`() {
    val described = describeAttachments(
      "замени голову",
      listOf(
        // Безымянный референс раньше выпадал из перечисления — и вся нумерация после него съезжала.
        file("01.webp", AttachmentRole.REFERENCE),
        file("граф3.jpg"),
        file("тема1.jpg"),
      ),
    )

    assertEquals(
      "замени голову\n\nИсходники по порядку: 1) референс: 01.webp; 2) персонаж: граф3.jpg; " +
        "3) персонаж: тема1.jpg. Обращайся к ним по номеру.",
      described,
    )
  }

  @Test
  fun `tags stay with their own number`() {
    val described = describeAttachments(
      "кадр",
      listOf(file("bg.jpg", AttachmentRole.BACKGROUND, listOf("ночь", "туман")), file("hero.png")),
    )

    assertTrue(described.contains("1) фон: bg.jpg (ночь, туман)"), described)
    assertTrue(described.contains("2) персонаж: hero.png"), described)
  }

  @Test
  fun `a single image is described without numbering`() {
    // Нумеровать нечего, и «обращайся по номеру» на одной картинке звучало бы издевательством.
    val described = describeAttachments("кадр", listOf(file("hero.png")))

    assertEquals("кадр\n\nИсходник — персонаж: hero.png.", described)
  }

  @Test
  fun `a lone nameless reference adds nothing to the prompt`() {
    // Обычный image-to-image: сказать про эту картинку нечего, и дописывать к промпту шум незачем.
    assertEquals("кадр", describeAttachments("кадр", listOf(file("ref.png", AttachmentRole.REFERENCE))))
  }

  @Test
  fun `no images means no tail at all`() {
    assertEquals("кадр", describeAttachments("кадр", emptyList()))
  }
}
