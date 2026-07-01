package com.aiopenpocket.agent.core.prompt

// @trace multi-chat-conversations-b3c93b

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.profile.stripToJsonObject
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Annotates a conversation from its raw chat log. After each assistant reply we make one background
 * LLM call that derives a title, a summary and tags. The chat history itself is never compressed —
 * it stays a verbatim log; this is purely the index on top of it.
 *
 * Tags are the only association between conversations, so the "knowledge base" emerges from tag
 * overlap (see [ConversationRepository.getConversationIdsByTags]) without any explicit graph.
 */
@SingleIn(AppScope::class)
class ConversationAnnotator @Inject constructor(
  private val llmClient: LlmClient,
  private val conversationRepository: ConversationRepository,
) {

  private val json = Json { ignoreUnknownKeys = true }

  /** Regenerate title/summary/tags for [conversationId] from its current messages. */
  suspend fun annotate(conversationId: String) {
    val messages = conversationRepository.getAllMessages(conversationId)
      .filter { it.role == "user" || it.role == "assistant" }
      .filter { it.content.isNotBlank() }
    if (messages.size < LlmContextConfig.MIN_MESSAGES_FOR_ANNOTATION) return

    val transcript = ContextWindowBuilder.formatMessagesForSummary(
      messages.takeLast(LlmContextConfig.ANNOTATION_MESSAGE_LIMIT),
    )
    val annotation = request(transcript) ?: return
    conversationRepository.updateAnnotation(
      id = conversationId,
      title = annotation.title,
      summary = annotation.summary?.take(LlmContextConfig.SUMMARY_MAX_CHARS),
      tags = annotation.tags.orEmpty().take(LlmContextConfig.MAX_TAGS),
    )
  }

  private suspend fun request(transcript: String): Annotation? {
    val userContent = buildString {
      appendLine("Диалог пользователя и ассистента:")
      appendLine(transcript)
      appendLine()
      appendLine("Верни СТРОГО JSON-объект без markdown и пояснений вида:")
      appendLine("""{"title": "...", "summary": "...", "tags": ["...", "..."]}""")
      appendLine("- title: короткий заголовок темы, до 6 слов, на русском.")
      appendLine("- summary: суть диалога (ключевые факты, решения, открытые задачи), до 500 слов, на русском.")
      appendLine("- tags: 2–5 коротких тегов в нижнем регистре (одно-два слова), на русском.")
    }

    val chat = listOf(
      ChatMessage(
        role = "system",
        content = "Ты анализируешь историю чата и возвращаешь только JSON. Без markdown, без текста вокруг.",
      ),
      ChatMessage(role = "user", content = userContent),
    )

    val builder = StringBuilder()
    var streamError: String? = null
    llmClient.streamChat(chat, tools = emptyList()).collect { event ->
      when (event) {
        is StreamEvent.TextDelta -> builder.append(event.content)
        is StreamEvent.Error -> streamError = event.message
        else -> Unit
      }
    }
    if (streamError != null) return null
    return parse(builder.toString())
  }

  /** Pull the JSON object out of the model's reply, tolerating stray prose or code fences. */
  private fun parse(raw: String): Annotation? {
    val jsonText = stripToJsonObject(raw) ?: return null
    return runCatching { json.decodeFromString<Annotation>(jsonText) }.getOrNull()
  }

  @Serializable
  private data class Annotation(
    @SerialName("title") val title: String? = null,
    @SerialName("summary") val summary: String? = null,
    @SerialName("tags") val tags: List<String>? = null,
  )
}
