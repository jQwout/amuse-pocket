package com.aiopenpocket.agent.core.lesson

// @trace kartinki-urokov-cherez-muse-image-fd6f8c

import com.aiopenpocket.agent.core.memory.model.Fact
import java.util.Locale

/**
 * "About me" from the profile — the user's own words. The fact is written by
 * MemoryRepository.saveUserProfile (onboarding and the lesson's "profile" step).
 */
const val ABOUT_ME_FACT_KEY = "user.extra_info"

/**
 * What from the profile goes to the image provider for "Draw it for me": only "about me".
 *
 * Name, form of address, age, gender and religion stay on the phone. An image without letters has no use
 * for a name, and the rest is sensitive data the provider does not need to know and from which models
 * readily draw stereotypes. The choice is kept here, in one function, not in the caller: it is a
 * privacy decision, and it must be visible and checked by a test.
 */
fun aboutMeFrom(profileFacts: List<Fact>): String =
  profileFacts.firstOrNull { it.key == ABOUT_ME_FACT_KEY }?.value?.trim().orEmpty()

/**
 * The "Draw it for me" prompt: the same lesson scene, but with details from the life of the person
 * looking at it.
 *
 * The idea and the composition stay from the authored prompt — the lesson explains the same thought,
 * just using the user's material. The "about me" text is cut: a long autobiography does not make the
 * frame more accurate, but carries away more personal data. The language is a hint for everyday
 * details; there are no letters in the image anyway (LessonImageGenerator adds the shared style).
 */
fun personalizedImagePrompt(basePrompt: String, aboutMe: String, languageCode: String): String {
  val about = aboutMe.trim().take(ABOUT_ME_MAX_CHARS)
  val language = Locale.forLanguageTag(languageCode).getDisplayLanguage(Locale.ENGLISH).ifBlank { languageCode }
  return "${basePrompt.trim()}\n\n" +
    "Adapt this scene to the person who will look at it, so they recognize their own life in it. " +
    "Keep the same idea and composition, but use details from what they wrote about themselves: \"$about\". " +
    "They speak $language."
}

private const val ABOUT_ME_MAX_CHARS = 400
