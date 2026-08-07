package com.aiopenpocket.genai

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoritesTest {

  private fun file(): File = File(createTempDirectory("genai-favorites").toFile(), "favorites.json")

  @Test
  fun `toggle adds, removes and survives a reread`() {
    val file = file()
    val favorites = Favorites(file)

    assertEquals(setOf("v/a"), favorites.toggle("v/a"))
    assertEquals(setOf("v/a", "v/b"), favorites.toggle("v/b"))
    assertEquals(setOf("v/b"), favorites.toggle("v/a"))
    // Второй экземпляр на тот же файл — это следующий запуск приложения.
    assertEquals(setOf("v/b"), Favorites(file).read())
  }

  @Test
  fun `a broken file reads as empty and does not block writing`() {
    // В отличие от истории заданий, здесь за файлом стоят галочки, а не деньги: отказаться писать
    // значило бы запереть избранное навсегда из-за одной лишней запятой.
    val file = file().apply { parentFile.mkdirs(); writeText("{не json") }
    val favorites = Favorites(file)

    assertEquals(emptySet(), favorites.read())
    assertEquals(setOf("v/a"), favorites.toggle("v/a"))
    assertEquals(setOf("v/a"), Favorites(file).read())
  }

  @Test
  fun `a missing file is not an error`() {
    assertEquals(emptySet(), Favorites(file()).read())
  }
}
