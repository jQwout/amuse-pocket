package com.aiopenpocket.ui.conversations

// @trace multi-chat-conversations-b3c93b

import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.ui.roadmap.ROADMAP_LEVELS
import com.aiopenpocket.ui.roadmap.lessonDisplayTitle
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list of chats showed lessons under their technical ids (`what_is_agent`, `how_model_sees`) because a
 * lesson's chat is stored with the lesson id as its title. A judge reads that list.
 */
class ConversationDisplayTitleTest {

  @After
  fun resetLanguage() {
    AppLanguage.code = AppLanguage.RU
  }

  private fun chat(title: String, tutorial: Boolean) = Conversation(
    id = "c-$title",
    title = title,
    createdAt = 0L,
    updatedAt = 0L,
    personaId = "sharp_partner",
    summary = null,
    isTutorial = tutorial,
  )

  // Gradle unit tests run from the module directory (app/); the fallback is the project root.
  private val lessonsDir: File = sequenceOf(File("src/main/assets/lessons"), File("app/src/main/assets/lessons"))
    .firstOrNull { it.isDirectory }
    ?: error("assets/lessons not found; working directory: ${File(".").absolutePath}")

  private val lessonIds: List<String> =
    lessonsDir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.sorted()

  @Test
  fun `a lesson chat shows the lesson name in English`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals("How a model sees", chat("how_model_sees", tutorial = true).displayTitle())
    assertEquals("Meta's models: who's who", chat("meta_models", tutorial = true).displayTitle())
  }

  @Test
  fun `a lesson chat shows the lesson name in Russian`() {
    AppLanguage.code = AppLanguage.RU
    assertEquals("Как модель видит", chat("how_model_sees", tutorial = true).displayTitle())
  }

  @Test
  fun `an ordinary chat keeps its own title even when it looks like a lesson id`() {
    AppLanguage.code = AppLanguage.EN
    assertEquals("how_model_sees", chat("how_model_sees", tutorial = false).displayTitle())
    assertEquals("Trip to Belgrade", chat("Trip to Belgrade", tutorial = false).displayTitle())
  }

  @Test
  fun `a lesson id the app does not know stays as it was stored`() {
    assertEquals("lesson_from_the_future", chat("lesson_from_the_future", tutorial = true).displayTitle())
    assertNull(lessonDisplayTitle("lesson_from_the_future"))
  }

  @Test
  fun `every lesson in the assets has a name in both languages, not its id`() {
    assertTrue("no lessons found", lessonIds.isNotEmpty())
    for (language in listOf(AppLanguage.RU, AppLanguage.EN)) {
      AppLanguage.code = language
      for (id in lessonIds) {
        val shown = chat(id, tutorial = true).displayTitle()
        assertNotEquals("$id shows as its own id with language=$language", id, shown)
        assertTrue("$id has a blank name with language=$language", shown.isNotBlank())
      }
    }
  }

  @Test
  fun `the English name is English`() {
    AppLanguage.code = AppLanguage.EN
    val russian = Regex("[Ѐ-ӿ]")
    val bad = lessonIds.filter { russian.containsMatchIn(chat(it, tutorial = true).displayTitle()) }
    assertTrue("Russian names under the English interface: $bad", bad.isEmpty())
  }

  private fun jsonTitle(file: File): String =
    Json.parseToJsonElement(file.readText()).jsonObject.getValue("title").jsonPrimitive.content

  /**
   * The name in the list of chats must be the name on the lesson itself. The Path and the list read their own
   * copy of the name (RoadmapLevels), the lesson JSON carries another, and the two had drifted: the capstone was
   * "design an agent" in the list and "design your own agent" at the top of the lesson, in the demo video.
   * The lesson JSON is the source of the text, so the list is held to it, in every language the lesson exists in.
   */
  @Test
  fun `the name in the list is the title of the lesson itself, for every lesson`() {
    assertTrue("no lessons found", lessonIds.isNotEmpty())
    for (id in lessonIds) {
      AppLanguage.code = AppLanguage.RU
      assertEquals("$id in Russian", jsonTitle(File(lessonsDir, "$id.json")), chat(id, tutorial = true).displayTitle())

      val english = File(File(lessonsDir, AppLanguage.EN), "$id.json")
      if (english.isFile) {
        AppLanguage.code = AppLanguage.EN
        assertEquals("$id in English", jsonTitle(english), chat(id, tutorial = true).displayTitle())
      }
    }
  }

  @Test
  fun `the Path levels are lessons that exist, so the list can name every one of them`() {
    val missing = ROADMAP_LEVELS.map { it.id }.filter { it !in lessonIds }
    assertTrue("Path levels without a lesson JSON: $missing", missing.isEmpty())
  }
}
