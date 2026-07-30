package com.aiopenpocket.genai

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GenaiModelCatalogTest {

  private fun userFile() = createTempDirectory("models").resolve("genai-models.json")

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
    file.parent.let(Files::createDirectories)
    Files.writeString(file, """{"models":[{"id":"my/model","modality":"IMAGE","priority":1}]}""")

    // В отличие от промптов, каталог моделей пользователь ведёт целиком под себя.
    val catalog = GenaiModelCatalog.load(file)
    assertEquals(listOf("my/model"), catalog.models.map { it.id })
  }

  @Test
  fun `a broken user file falls back to the built-in list, not to three emergency models`() {
    val file = userFile()
    file.parent.let(Files::createDirectories)
    Files.writeString(file, """{"models":[{"id":"my/model",]}""")

    val builtIn = GenaiModelCatalog.load(null)
    val loaded = GenaiModelCatalog.load(file)

    // Одна опечатка в своём файле не должна молча уносить половину подсказок.
    assertEquals(builtIn.models.map { it.id }, loaded.models.map { it.id })
    assertTrue(loaded.models.size > GenaiModelCatalog.FALLBACK.models.size)
  }

  @Test
  fun `an empty user file falls back too`() {
    val file = userFile()
    file.parent.let(Files::createDirectories)
    Files.writeString(file, """{"models":[]}""")

    assertEquals(GenaiModelCatalog.load(null).models.size, GenaiModelCatalog.load(file).models.size)
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
