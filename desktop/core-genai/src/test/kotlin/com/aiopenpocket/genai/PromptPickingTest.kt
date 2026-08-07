package com.aiopenpocket.genai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptPickingTest {

  private fun preset(
    id: String,
    title: String = id,
    prompt: String = "текст",
    category: String = "Предметка",
    tags: List<String> = emptyList(),
  ) = PromptPreset(id = id, title = title, prompt = prompt, category = category, tags = tags)

  private fun cards(vararg presets: PromptPreset, usage: Map<String, Int> = emptyMap()) =
    buildPromptCards(presets.toList(), usage, favorites = setOf("fav"), ownIds = setOf("mine"))

  @Test
  fun `a preset without a category lands in one shared section, not in an empty one`() {
    val card = cards(preset("x", category = "")).single()

    assertEquals(OTHER_CATEGORY, card.category)
  }

  @Test
  fun `categories come from the library itself and keep its order`() {
    val list = cards(
      preset("a", category = "Предметка"),
      preset("b", category = "Портрет"),
      preset("c", category = "Предметка"),
    )

    // Порядок из файла, а не по алфавиту: библиотека разложена по смыслу.
    assertEquals(listOf("Предметка" to 2, "Портрет" to 1), promptCategories(list))
  }

  @Test
  fun `scopes answer where a preset came from, not what it is about`() {
    val list = cards(preset("fav"), preset("mine"), preset("plain"))

    assertEquals(3, scopeCount(list, PromptScope.ALL))
    assertEquals(1, scopeCount(list, PromptScope.FAVORITES))
    assertEquals(1, scopeCount(list, PromptScope.MINE))
  }

  @Test
  fun `search looks inside the prompt text, not only at the title`() {
    val card = cards(
      preset("a", title = "Предметная съёмка", prompt = "мягкий студийный свет", tags = listOf("каталог")),
    ).single()

    assertTrue(card.matches("студийный"), "по тексту не находится")
    assertTrue(card.matches("каталог"), "по тегу не находится")
    assertTrue(card.matches("ПРЕДМЕТ"), "по названию и категории не находится")
    assertFalse(card.matches("портрет"))
  }

  @Test
  fun `popular sorting falls back to the alphabet while nothing has been used`() {
    // Поначалу счётчики нулевые у всех, и без второго ключа порядок зависел бы от раскладки файла.
    val list = cards(preset("b", title = "Бета"), preset("a", title = "Альфа"), preset("v", title = "Вега"))

    assertEquals(listOf("Альфа", "Бета", "Вега"), sortPromptCards(list, PromptSort.POPULAR).map { it.title })
  }

  @Test
  fun `popular sorting puts the most used first`() {
    val list = cards(
      preset("a", title = "Альфа"),
      preset("b", title = "Бета"),
      usage = mapOf("b" to 7),
    )

    assertEquals(listOf("Бета", "Альфа"), sortPromptCards(list, PromptSort.POPULAR).map { it.title })
  }

  @Test
  fun `own presets go first when asked`() {
    val list = cards(preset("a", title = "Альфа"), preset("mine", title = "Яндекс"))

    assertEquals(listOf("Яндекс", "Альфа"), sortPromptCards(list, PromptSort.MINE_FIRST).map { it.title })
  }

  @Test
  fun `a never used preset shows no counter at all`() {
    assertEquals("", usesLabel(0))
    assertEquals("1 исп.", usesLabel(1))
    assertEquals("34 исп.", usesLabel(34))
  }

  @Test
  fun `the summary only promises what the preset actually has`() {
    val plain = cards(preset("a", prompt = "просто текст")).single()
    assertEquals("Предметка", promptSummary(plain))

    val withHoles = cards(
      preset("b", prompt = "{объект} на подиуме, фон {цвет фона}"),
      usage = mapOf("b" to 11),
    ).single()
    assertEquals(
      "Предметка · использован 11 раз · в фигурных скобках — то, что подставляется под кадр",
      promptSummary(withHoles),
    )
  }

  @Test
  fun `placeholders are listed once and never include the wrap marker`() {
    val preset = preset("a", prompt = "{объект} на фоне {цвет фона}, снова {объект}. ${PromptPreset.PLACEHOLDER}")

    // Место для уже набранного промпта — не поле для заполнения, предлагать его вписать нельзя.
    assertEquals(listOf("{объект}", "{цвет фона}"), preset.placeholders)
  }

  @Test
  fun `append glues to what is already typed instead of wiping it`() {
    val addition = preset("a", prompt = "мягкий свет")

    assertEquals("кот, мягкий свет", addition.appendTo("кот"))
    assertEquals("кот, мягкий свет", addition.appendTo("кот,"), "запятая задвоилась")
    assertEquals("мягкий свет", addition.appendTo("   "))
  }

  @Test
  fun `an addition preset wraps the typed text on both insert and append`() {
    // У приписки склейка через запятую оставила бы «{основной промпт}» прямо в тексте запроса.
    val note = preset("n", prompt = "${PromptPreset.PLACEHOLDER}. Избегать: лишние пальцы")

    assertEquals("кот. Избегать: лишние пальцы", note.applyTo("кот"))
    assertEquals("кот. Избегать: лишние пальцы", note.appendTo("кот"))
  }
}
