package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Отбор и порядок в окне выбора модели.
 *
 * Главное, что здесь защищается, — правило «незаявленное не равно запрещённому»: молчание модели о
 * своих параметрах не повод убрать её из подходящих. Второе — что цена в разных единицах не
 * смешивается в одной сортировке.
 */
class ModelPickingTest {

  private fun entry(
    id: String,
    priority: Int = 1,
    created: Long = 0,
    pricing: List<ImagePrice> = emptyList(),
    image: ImageCapabilities? = null,
  ) = GenaiModelEntry(
    id = id,
    modality = GenerationModality.IMAGE,
    priority = priority,
    created = created,
    pricing = pricing,
    image = image,
  )

  private fun job(model: String, status: JobStatus = JobStatus.DONE, costUsd: Double? = null) =
    GenerationJob(
      id = model + status + costUsd,
      request = GenerationRequest(GenerationModality.IMAGE, "промпт", model),
      createdAtEpochMs = 1,
      status = status,
      costUsd = costUsd,
    )

  @Test
  fun `a model that declares nothing is never called unsuitable`() {
    // Пустые списки — это «модель молчит», а не «модель не умеет». Записать её в неподходящие
    // значило бы спрятать рабочий вариант на основании собственной догадки.
    val silent = entry("vendor/silent", image = ImageCapabilities())
    val context = PickerContext(attachments = 5, aspectRatio = "4:3", resolution = "4K")

    assertNull(misfitReason(silent, context))
    // И запись вообще без блока параметров — тоже.
    assertNull(misfitReason(entry("vendor/bare"), context))
  }

  @Test
  fun `more attachments than the declared reference limit is a misfit`() {
    val model = entry("krea/krea-2-large", image = ImageCapabilities(maxReferences = 1))

    assertEquals("берёт до 1 референса", misfitReason(model, PickerContext(attachments = 3)))
    assertNull(misfitReason(model, PickerContext(attachments = 1)))
    // Ноль референсов в заявке — то же молчание: у живых моделей поле есть всегда.
    assertNull(misfitReason(entry("v/m", image = ImageCapabilities()), PickerContext(attachments = 9)))
  }

  @Test
  fun `an aspect ratio or tier outside the declared list is a misfit`() {
    val model = entry(
      "v/m",
      image = ImageCapabilities(resolutions = listOf("1K", "2K"), aspectRatios = listOf("1:1", "16:9")),
    )

    assertEquals("не заявляет 4:3", misfitReason(model, PickerContext(aspectRatio = "4:3")))
    assertEquals("не заявляет 4K", misfitReason(model, PickerContext(resolution = "4K")))
    assertNull(misfitReason(model, PickerContext(aspectRatio = "16:9", resolution = "2K")))
  }

  @Test
  fun `cheapest sorting pushes incomparable prices to the end`() {
    val cards = buildCards(
      entries = listOf(
        entry("v/megapixel", pricing = listOf(ImagePrice("megapixel", 0.001))),
        entry("v/dear", pricing = listOf(ImagePrice("image", 0.09))),
        entry("v/nothing"),
        entry("v/cheap", pricing = listOf(ImagePrice("image", 0.03))),
      ),
      jobs = emptyList(),
      favorites = emptySet(),
    )

    val order = sortCards(cards, PickerSort.CHEAPEST).map { it.id }
    // $0.001 за мегапиксель — самое маленькое число в списке, и именно поэтому оно не должно
    // оказаться первым: это цена другой вещи.
    assertEquals(listOf("v/cheap", "v/dear", "v/megapixel", "v/nothing"), order)
  }

  @Test
  fun `cheapest sorting follows the chosen tier`() {
    val cards = buildCards(
      entries = listOf(
        entry("v/flat", pricing = listOf(ImagePrice("image", 0.14))),
        entry("v/tiered", pricing = listOf(ImagePrice("image", 0.13), ImagePrice("image", 0.17, "4k"))),
      ),
      jobs = emptyList(),
      favorites = emptySet(),
    )

    assertEquals(listOf("v/tiered", "v/flat"), sortCards(cards, PickerSort.CHEAPEST).map { it.id })
    // На 4K та же пара переворачивается: сортировать по базовой цене значило бы показать порядок,
    // которого не будет в счёте.
    assertEquals(listOf("v/flat", "v/tiered"), sortCards(cards, PickerSort.CHEAPEST, "4K").map { it.id })
  }

  @Test
  fun `newest sorting uses the creation date from the live catalog`() {
    val cards = buildCards(
      entries = listOf(entry("v/old", created = 100), entry("v/new", created = 900)),
      jobs = emptyList(),
      favorites = emptySet(),
    )

    assertEquals(listOf("v/new", "v/old"), sortCards(cards, PickerSort.NEWEST).map { it.id })
  }

  @Test
  fun `runs count every launch, the average only the ones that reported a cost`() {
    val cards = buildCards(
      entries = listOf(entry("v/m")),
      jobs = listOf(
        job("v/m", costUsd = 0.004),
        job("v/m", costUsd = 0.006),
        job("v/m", JobStatus.FAILED),
        job("v/other", costUsd = 1.0),
      ),
      favorites = setOf("v/m"),
    )

    val card = cards.single()
    assertEquals(3, card.runs, "«запускали N раз» — это про знакомство с моделью, а не про удачи")
    assertEquals(0.005, card.averageUsd)
    assertTrue(card.favorite)
  }

  @Test
  fun `tags repeat only what the model declared`() {
    val model = entry(
      "v/m",
      image = ImageCapabilities(
        resolutions = listOf("1K", "2K", "4K"),
        outputFormats = listOf("png", "jpeg"),
        maxReferences = 8,
      ),
    )

    assertEquals(listOf("реф. до 8", "до 4K", "png/jpeg"), capabilityTags(model))
    assertEquals(emptyList(), capabilityTags(entry("v/bare")))
    assertTrue(capabilitySentence(model).startsWith("Берёт до 8 референсов, отдаёт до 4K"))
    assertEquals(
      "Модель не сообщила о себе параметров — уйдёт только промпт.",
      capabilitySentence(entry("v/silent", image = ImageCapabilities())),
    )
  }

  @Test
  fun `top resolution is picked by size, not by order or alphabet`() {
    // По алфавиту «512» больше «4K», а порядок в списке провайдер не обещал.
    assertEquals("4K", topResolution(listOf("512", "1K", "4K", "2K")))
    assertEquals("2K", topResolution(listOf("2K", "1K")))
    assertNull(topResolution(emptyList()))
    assertNull(topResolution(listOf("auto")))
  }

  @Test
  fun `context summary declines the file count`() {
    assertEquals("1 файл, 4:3, 1K", contextSummary(PickerContext(1, "4:3", "1K")))
    assertEquals("2 файла", contextSummary(PickerContext(attachments = 2)))
    assertEquals("11 файлов", contextSummary(PickerContext(attachments = 11)))
    assertEquals("21 файл", contextSummary(PickerContext(attachments = 21)))
    assertEquals("", contextSummary(PickerContext()))
  }

  @Test
  fun `search matches the vendor and the description, not only the slug`() {
    val card = buildCards(
      entries = listOf(
        GenaiModelEntry(
          id = "black-forest-labs/flux.2-pro",
          modality = GenerationModality.IMAGE,
          vendorName = "Black Forest Labs: FLUX.2 Pro",
          description = "Delivers clean light and predictable pricing.",
        ),
      ),
      jobs = emptyList(),
      favorites = emptySet(),
    ).single()

    assertTrue(card.matches("black-forest"), "вендор не находится")
    assertTrue(card.matches("PREDICTABLE"), "описание видно на экране, но не находится")
    assertTrue(card.matches(""))
    assertTrue(!card.matches("seedream"))
  }
}
