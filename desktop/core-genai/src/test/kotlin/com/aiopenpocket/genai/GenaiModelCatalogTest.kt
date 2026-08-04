package com.aiopenpocket.genai

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenaiModelCatalogTest {

  private fun userFile() = File(createTempDirectory("models").toFile(), "genai-models.json")

  @Test
  fun `built-in catalog covers every modality`() {
    val catalog = GenaiModelCatalog.load(null)
    for (modality in GenerationModality.entries) {
      assertTrue(catalog.forModality(modality).isNotEmpty(), "нет моделей для $modality")
      assertTrue(catalog.defaultFor(modality) != null)
    }
  }

  @Test
  fun `a user file replaces the built-in list`() {
    val file = userFile()
    file.parentFile.mkdirs()
    file.writeText("""{"models":[{"id":"my/model","modality":"IMAGE","priority":1}]}""")

    // В отличие от промптов, каталог моделей пользователь ведёт целиком под себя.
    val catalog = GenaiModelCatalog.load(file)
    assertEquals(listOf("my/model"), catalog.models.map { it.id })
  }

  @Test
  fun `a broken user file falls back to the built-in list, not to three emergency models`() {
    val file = userFile()
    file.parentFile.mkdirs()
    file.writeText("""{"models":[{"id":"my/model",]}""")

    val builtIn = GenaiModelCatalog.load(null)
    val loaded = GenaiModelCatalog.load(file)

    // Одна опечатка в своём файле не должна молча уносить половину подсказок.
    assertEquals(builtIn.models.map { it.id }, loaded.models.map { it.id })
    assertTrue(loaded.models.size > GenaiModelCatalog.FALLBACK.models.size)
  }

  @Test
  fun `an empty user file falls back too`() {
    val file = userFile()
    file.parentFile.mkdirs()
    file.writeText("""{"models":[]}""")

    assertEquals(GenaiModelCatalog.load(null).models.size, GenaiModelCatalog.load(file).models.size)
  }

  @Test
  fun `loadOverride returns the user list alone, without the built-in one`() {
    val file = userFile()
    file.parentFile.mkdirs()
    file.writeText("""{"models":[{"id":"my/model","modality":"IMAGE","priority":1}]}""")

    // Живому каталогу нужно отличать «пользователь так велел» от «встроенная подсказка»:
    // первое он отменять не вправе, второе замещает.
    assertEquals(listOf("my/model"), GenaiModelCatalog.loadOverride(file).models.map { it.id })
  }

  @Test
  fun `loadOverride is empty when there is no user file`() {
    assertTrue(GenaiModelCatalog.loadOverride(null).models.isEmpty())
    assertTrue(GenaiModelCatalog.loadOverride(userFile()).models.isEmpty())
  }

  @Test
  fun `a slug without a label still reads like a name`() {
    // Живой каталог подписей не приносит, и без этого весь список — стена одинаковых слагов.
    assertEquals("Gemini 3.1 Flash Lite Image", humanModelName("google/gemini-3.1-flash-lite-image"))
    assertEquals("GPT 5 Image Mini", humanModelName("openai/gpt-5-image-mini"))
    assertEquals("Kokoro 82M", humanModelName("hexgrad/kokoro-82m"))
    // Токен с цифрой идёт в верхнем регистре целиком, и здесь это попадает в точку: вендор пишет
    // «FLUX.2», и встроенный каталог подписан ровно так же.
    assertEquals("FLUX.2 Pro", humanModelName("black-forest-labs/flux.2-pro"))
  }

  @Test
  fun `the vendor is split off the slug`() {
    assertEquals("google", modelVendor("google/gemini-3-pro-image"))
    assertEquals("", modelVendor("bare-model-id"))
  }

  @Test
  fun `an explicit label beats the derived name`() {
    // Своя подпись в genai-models.json — прямое указание пользователя, догадка её не перебивает.
    val entry = GenaiModelEntry("google/gemini-3-pro-image", GenerationModality.IMAGE, label = "Моя картинка")
    assertEquals("Моя картинка", entry.displayName)

    val bare = GenaiModelEntry("google/gemini-3-pro-image", GenerationModality.IMAGE)
    assertEquals("Gemini 3 Pro Image", bare.displayName)
  }

  @Test
  fun `models are offered in priority order`() {
    val catalog = GenaiModelCatalog(
      listOf(
        GenaiModelEntry("second", GenerationModality.IMAGE, priority = 2),
        GenaiModelEntry("first", GenerationModality.IMAGE, priority = 1),
      ),
    )

    assertEquals(listOf("first", "second"), catalog.forModality(GenerationModality.IMAGE).map { it.id })
    assertEquals("first", catalog.defaultFor(GenerationModality.IMAGE)?.id)
  }
}
