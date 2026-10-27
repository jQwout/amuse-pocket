package com.aiopenpocket.agent.core.lesson

import com.aiopenpocket.agent.core.memory.model.Fact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Draw it for me": what from the profile goes to the image provider and how the prompt is built from it.
 *
 * The main thing here is privacy: the provider gets "about me" and the language, and nothing else from
 * the profile.
 */
class LessonImagePersonalizationTest {

  private fun fact(key: String, value: String) =
    Fact(id = key, key = key, value = value, category = "profile", createdAt = 0, updatedAt = 0)

  private val profile = listOf(
    fact("user.name", "Саша Петрова"),
    fact("user.preferred_address", "Саша"),
    fact("user.age", "34"),
    fact("user.gender", "женский"),
    fact("user.religion", "православие"),
    fact("user.extra_info", "  Пеку хлеб, вожу дочку на плавание, работаю бухгалтером  "),
  )

  /** Name, age, gender and religion are not needed by the image provider — only "about me" goes out. */
  @Test
  fun `only the about-me text leaves the profile`() {
    val aboutMe = aboutMeFrom(profile)

    assertEquals("Пеку хлеб, вожу дочку на плавание, работаю бухгалтером", aboutMe)
    val prompt = personalizedImagePrompt("A robot reads a book.", aboutMe, "ru")
    listOf("Саша", "Петрова", "34", "женский", "православие").forEach {
      assertFalse("в промпт утекло «$it»: $prompt", prompt.contains(it))
    }
  }

  @Test
  fun `no about-me text means nothing to personalize`() {
    assertEquals("", aboutMeFrom(profile.filterNot { it.key == ABOUT_ME_FACT_KEY }))
    assertEquals("", aboutMeFrom(emptyList()))
  }

  @Test
  fun `the lesson scene stays and the learner's life and language are added`() {
    val prompt = personalizedImagePrompt("  A robot librarian pulls pages from a shelf.  ", "Пеку хлеб", "ru")

    assertTrue(prompt, prompt.startsWith("A robot librarian pulls pages from a shelf.\n\n"))
    assertTrue(prompt, prompt.contains("\"Пеку хлеб\""))
    assertTrue(prompt, prompt.contains("They speak Russian."))
    assertTrue(prompt, prompt.contains("Keep the same idea and composition"))
  }

  /** A long autobiography does not make the frame more precise, it only carries away more personal data. */
  @Test
  fun `a long about-me text is cut`() {
    val prompt = personalizedImagePrompt("A robot.", "а".repeat(1000), "ru")

    assertTrue(prompt.contains("а".repeat(400)))
    assertFalse(prompt.contains("а".repeat(401)))
  }

  @Test
  fun `an unknown language code is passed as is`() {
    assertTrue(personalizedImagePrompt("A robot.", "x", "").contains("They speak ."))
    assertTrue(personalizedImagePrompt("A robot.", "x", "sr").contains("They speak Serbian."))
  }
}
