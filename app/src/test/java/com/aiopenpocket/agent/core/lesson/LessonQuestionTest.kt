package com.aiopenpocket.agent.core.lesson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonQuestionTest {

  @Test
  fun `card step context is extracted from card fields, not the empty content`() {
    // Карточка concept публикуется с content="" — контекст надо собрать из term/definition.
    val step = LessonStep(type = "concept", term = "Эмбеддинг", definition = "числа, ловящие смысл")
    val ctx = LessonQuestion.stepContext(lessonTitle = "RAG", step = step)
    assertTrue("несёт тему урока", ctx.contains("RAG"))
    assertTrue("несёт термин карточки", ctx.contains("Эмбеддинг"))
    assertTrue("несёт определение карточки", ctx.contains("числа, ловящие смысл"))
  }

  @Test
  fun `text step context uses the paragraph text`() {
    val step = LessonStep(type = "text", text = "Токен — кусочек текста.")
    assertTrue(LessonQuestion.stepContext("Токены", step).contains("Токен — кусочек текста."))
  }

  @Test
  fun `null or empty step yields empty context`() {
    assertEquals("", LessonQuestion.stepContext("Урок", null))
    assertEquals("", LessonQuestion.stepContext("Урок", LessonStep(type = "image")))
  }
}
