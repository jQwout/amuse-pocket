package com.aiopenpocket.agent.core.lesson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonQuestionTest {

  @Test
  fun `card step context is extracted from card fields, not the empty content`() {
    // The concept card is posted with content="" — the context has to be built from term/definition.
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
  fun `segment and video steps give the tutor their instruction`() {
    // A side question on the SAM step must see what the learner was asked to do: the card posts an empty content.
    val segment = LessonStep(type = "segment", instruction = "Назови одну вещь по-английски")
    assertTrue(LessonQuestion.stepContext("Как модель видит", segment).contains("Назови одну вещь по-английски"))
  }

  @Test
  fun `null or empty step yields empty context`() {
    assertEquals("", LessonQuestion.stepContext("Урок", null))
    assertEquals("", LessonQuestion.stepContext("Урок", LessonStep(type = "image")))
  }
}
