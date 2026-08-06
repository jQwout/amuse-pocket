package com.aiopenpocket.genai

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptLibraryTest {

  private fun userFile() = File(createTempDirectory("prompts").toFile(), "genai-prompts.json")

  @Test
  fun `built-in presets load without a user file`() {
    val library = PromptLibrary.load(null)
    assertTrue(library.presets.isNotEmpty(), "встроенные пресеты не прочитались из ресурса")
    assertTrue(library.presets.all { it.prompt.isNotBlank() })
  }

  @Test
  fun `user presets merge with built-in instead of replacing them`() {
    val file = userFile()
    val builtInCount = PromptLibrary.load(null).presets.size
    val merged = PromptLibrary.saveUserPreset(
      file,
      PromptPreset(id = "my-own", title = "Своё", prompt = "мой промпт", modality = GenerationModality.IMAGE),
    )

    // Ключевое отличие от каталога моделей: свой файл дополняет встроенные, а не затирает их.
    assertEquals(builtInCount + 1, merged.presets.size)
    assertTrue(merged.presets.any { it.id == "my-own" })
  }

  @Test
  fun `user preset with a built-in id wins`() {
    val file = userFile()
    val builtInId = PromptLibrary.load(null).presets.first().id
    val merged = PromptLibrary.saveUserPreset(
      file,
      PromptPreset(id = builtInId, title = "Переопределено", prompt = "моя версия"),
    )

    val matching = merged.presets.filter { it.id == builtInId }
    assertEquals(1, matching.size, "переопределение должно заменять, а не дублировать")
    assertEquals("моя версия", matching.single().prompt)
  }

  @Test
  fun `saving the same preset twice does not create duplicates`() {
    val file = userFile()
    val preset = PromptPreset(id = "dup", title = "Дубль", prompt = "первый вариант")
    PromptLibrary.saveUserPreset(file, preset)
    val after = PromptLibrary.saveUserPreset(file, preset.copy(prompt = "второй вариант"))

    val matching = after.presets.filter { it.id == "dup" }
    assertEquals(1, matching.size)
    assertEquals("второй вариант", matching.single().prompt)
  }

  @Test
  fun `deleting a user preset brings the built-in one back`() {
    val file = userFile()
    val builtIn = PromptLibrary.load(null).presets.first()
    PromptLibrary.saveUserPreset(file, PromptPreset(builtIn.id, "Переопределено", "моя версия"))
    val after = PromptLibrary.deleteUserPreset(file, builtIn.id)

    assertEquals(builtIn.prompt, after.presets.single { it.id == builtIn.id }.prompt)
  }

  @Test
  fun `presets without modality fit every modality`() {
    val library = PromptLibrary(
      listOf(
        PromptPreset("any", "Любая", "текст"),
        PromptPreset("img", "Картинка", "текст", modality = GenerationModality.IMAGE),
      ),
    )

    assertEquals(listOf("any", "img"), library.forModality(GenerationModality.IMAGE).map { it.id })
    assertEquals(listOf("any"), library.forModality(GenerationModality.VIDEO).map { it.id })
  }

  @Test
  fun `broken user file does not take the built-in presets down with it`() {
    val file = userFile()
    file.parentFile.mkdirs()
    file.writeText("{ это не json")

    // Порченый файл должен деградировать до встроенных, а не ронять экран генерации.
    assertEquals(PromptLibrary.load(null).presets.size, PromptLibrary.load(file).presets.size)
  }

  @Test
  fun `id is derived from the title and stays stable`() {
    assertEquals("kot-v-skafandre", PromptLibrary.idFor("Kot v skafandre"))
    assertEquals(PromptLibrary.idFor("Моя заготовка"), PromptLibrary.idFor("  Моя заготовка  "))
    assertTrue(PromptLibrary.idFor("!!!").isNotBlank(), "пустой слаг должен подменяться, а не давать пустой id")
  }

  @Test
  fun `a preset with the placeholder wraps the current prompt instead of wiping it`() {
    val addition = PromptPreset(
      id = "tail",
      title = "Приписка",
      prompt = "${PromptPreset.PLACEHOLDER}. Избегать: лишние пальцы",
    )

    assertEquals("кот в скафандре. Избегать: лишние пальцы", addition.applyTo("  кот в скафандре  "))
  }

  @Test
  fun `a preset without the placeholder replaces the prompt`() {
    val standalone = PromptPreset(id = "shot", title = "Съёмка", prompt = "предметная фотография")
    assertEquals("предметная фотография", standalone.applyTo("что-то старое"))
  }

  @Test
  fun `the built-in addition preset really is an addition`() {
    // Данные и поведение должны сходиться: пресет называется припиской — значит не затирает.
    val tail = PromptLibrary.load(null).presets.single { it.id == "negative-tail" }
    assertTrue(PromptPreset.PLACEHOLDER in tail.prompt, "во встроенной приписке нет плейсхолдера")
    assertTrue(tail.applyTo("кот").startsWith("кот"), tail.applyTo("кот"))
  }

  @Test
  fun `a broken user file refuses the write instead of wiping every saved preset`() {
    // Одна лишняя запятая в genai-prompts.json — и следующее «Сохранить» писало «пустой список +
    // новый пресет», то есть стирало всё накопленное, молча и с видом успеха.
    val file = File(createTempDirectory("prompts").toFile(), "genai-prompts.json")
    file.parentFile.mkdirs()
    file.writeText("{ это не json ")

    assertFailsWith<IllegalStateException> {
      PromptLibrary.saveUserPreset(file, PromptPreset("p", "Заголовок", "промпт", GenerationModality.IMAGE))
    }
    assertEquals("{ это не json ", file.readText(), "битый файл обязан остаться нетронутым")
  }
}
