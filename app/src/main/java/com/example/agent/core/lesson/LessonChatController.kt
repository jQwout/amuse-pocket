package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * Драйвит шаги JSON-урока как СТРОГИЙ контракт прямо в чате: код решает последовательность шагов и
 * что рисовать, публикуя по одному сообщению ассистента на шаг. LLM в этот поток не вмешивается —
 * чётко разделяя «шаги урока» (код) и «команды для LLM-чата» (явные хода, которых тут нет).
 *
 * Контракт: 1 шаг = ровно 1 assistant-сообщение. Поэтому позицию в уроке можно восстановить простым
 * счётом assistant-сообщений (этим пользуется [com.aiopenpocket.ui.chat.ChatViewModel]).
 */
@Inject
@SingleIn(AppScope::class)
class LessonChatController(
  private val conversationRepository: ConversationRepository,
) {

  /** Публикует шаг урока [index] как одно сообщение ассистента: text → текст, quiz → карточка. */
  suspend fun postStep(conversationId: String, lesson: Lesson, index: Int) {
    val step = lesson.steps.getOrNull(index) ?: return
    when (step.type) {
      "quiz" -> conversationRepository.addMessage(
        conversationId, "assistant", "", cardSpec = buildQuizCardSpec(step),
      )
      else -> conversationRepository.addMessage(conversationId, "assistant", step.text)
    }
  }

  /** Финальная карточка «урок пройден»: её кнопка отметит уровень пройденным и вернёт на список. */
  suspend fun postCompletion(conversationId: String, lesson: Lesson) {
    val params = JSONObject()
      .put("title", "Урок «${lesson.title}» пройден!")
      .put("subtitle", "Отличная работа.")
      .put("button_label", "Готово")
    val spec = JSONObject().put("template", "lesson_complete").put("params", params).toString()
    conversationRepository.addMessage(conversationId, "assistant", "", cardSpec = spec)
  }

  /** Собирает cardSpec для интерактивной карточки `lesson_quiz` из quiz-шага урока. */
  private fun buildQuizCardSpec(step: LessonStep): String {
    val questions = JSONArray()
    step.questions.forEach { q ->
      questions.put(
        JSONObject()
          .put("q", q.q)
          .put("options", JSONArray(q.options))
          .put("correct", q.correct)
          .put("explain", q.explain),
      )
    }
    val params = JSONObject().put("questions", questions)
    return JSONObject().put("template", "lesson_quiz").put("params", params).toString()
  }
}
