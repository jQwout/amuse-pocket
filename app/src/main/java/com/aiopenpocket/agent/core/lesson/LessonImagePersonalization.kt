package com.aiopenpocket.agent.core.lesson

// @trace kartinki-urokov-cherez-muse-image-fd6f8c

import com.aiopenpocket.agent.core.memory.model.Fact
import java.util.Locale

/**
 * «О себе» из профиля — свои слова пользователя. Факт пишет MemoryRepository.saveUserProfile
 * (онбординг и шаг урока «profile»).
 */
const val ABOUT_ME_FACT_KEY = "user.extra_info"

/**
 * Что из профиля уходит провайдеру картинок для «Нарисуй под меня»: только «о себе».
 *
 * Имя, обращение, возраст, пол и вероисповедание остаются на телефоне. Картинке без букв имя ни к
 * чему, а остальное — чувствительные данные, которые провайдеру знать не нужно и по которым модель
 * охотно рисует стереотипы. Выбор держим здесь, одной функцией, а не в вызывающем: это решение о
 * приватности, и оно должно быть видно и проверено тестом.
 */
fun aboutMeFrom(profileFacts: List<Fact>): String =
  profileFacts.firstOrNull { it.key == ABOUT_ME_FACT_KEY }?.value?.trim().orEmpty()

/**
 * Промпт «Нарисуй под меня»: та же сцена урока, но с деталями из жизни того, кто на неё смотрит.
 *
 * Идея и композиция остаются от авторского промпта — урок объясняет ту же мысль, просто на
 * материале пользователя. Текст «о себе» обрезан: длинная автобиография кадр точнее не делает, а
 * личного увозит больше. Язык — подсказка для бытовых деталей; букв на картинке всё равно нет
 * (общий стиль добавляет LessonImageGenerator).
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
