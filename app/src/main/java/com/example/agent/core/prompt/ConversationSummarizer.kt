package com.aiopenpocket.agent.core.prompt

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
class ConversationSummarizer @Inject constructor(
  private val llmClient: LlmClient,
  private val conversationRepository: ConversationRepository,
) {

  suspend fun summarizeMiddleIfNeeded(conversationId: String) {
    val all = conversationRepository.getAllMessages(conversationId)
    val head = LlmContextConfig.HEAD_MESSAGE_COUNT
    val tail = LlmContextConfig.TAIL_MESSAGE_COUNT
    if (all.size <= head + tail) return

    val middle = all.drop(head).dropLast(tail)
    if (middle.size < LlmContextConfig.MIN_MIDDLE_MESSAGES_FOR_SUMMARY) return

    val existing = conversationRepository.getConversation(conversationId)?.summary
    val middleText = ContextWindowBuilder.formatMessagesForSummary(middle)
    val newSummary = requestSummary(existing, middleText) ?: return
    val clipped = newSummary.take(LlmContextConfig.SUMMARY_MAX_CHARS)
    conversationRepository.updateSummary(conversationId, clipped)
  }

  private suspend fun requestSummary(existingSummary: String?, middleText: String): String? {
    val userContent = buildString {
      if (!existingSummary.isNullOrBlank()) {
        appendLine("Предыдущее резюме середины диалога:")
        appendLine(existingSummary.trim())
        appendLine()
        appendLine("Новый фрагмент для включения в резюме:")
      } else {
        appendLine("Сожми середину диалога в краткое резюме:")
      }
      appendLine(middleText)
      appendLine()
      appendLine(
        "Верни только обновлённое резюме (до 500 слов): ключевые факты, решения, " +
          "открытые задачи. Без дословных цитат. На русском.",
      )
    }

    val messages = listOf(
      ChatMessage(
        role = "system",
        content = "Ты сжимаешь историю чата для экономии контекста. Пиши кратко и по делу.",
      ),
      ChatMessage(role = "user", content = userContent),
    )

    val builder = StringBuilder()
    var streamError: String? = null
    llmClient.streamChat(messages, tools = emptyList()).collect { event ->
      when (event) {
        is StreamEvent.TextDelta -> builder.append(event.content)
        is StreamEvent.Error -> streamError = event.message
        else -> Unit
      }
    }
    if (streamError != null) return null
    return builder.toString().trim().ifBlank { null }
  }
}
