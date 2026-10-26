package com.aiopenpocket.agent.core.prompt

// @trace multi-chat-conversations-b3c93b

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.memory.repository.ConversationRepository
import com.aiopenpocket.agent.core.profile.stripToJsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import java.util.concurrent.ConcurrentHashMap

/**
 * Annotates a conversation from its raw chat log. After each assistant reply we make one background
 * LLM call that derives a title, a summary and tags. The chat history itself is never compressed —
 * it stays a verbatim log; this is purely the index on top of it.
 *
 * Tags are the only association between conversations, so the "knowledge base" emerges from tag
 * overlap (see [ConversationRepository.getConversationIdsByTags]) without any explicit graph.
 */
@SingleIn(AppScope::class)
@Inject
class ConversationAnnotator(
  private val llmClient: LlmClient,
  private val conversationRepository: ConversationRepository,
  // The body of this call is a verbatim piece of the conversation. It must go wherever the chat itself
  // goes: the LlmProvider.NIM default used to apply here, and the conversation went to NVIDIA even for
  // someone who had deliberately moved to another vendor.
  private val selectedProvider: SelectedLlmProvider,
) {

  private val json = Json { ignoreUnknownKeys = true }

  /**
   * The number of "annotatable" messages at the last annotation, by conversationId. Gives a stable
   * "once per N messages" gate regardless of the counter's parity: the old `count % N == 0` stopped
   * firing forever in conversations with an odd number of messages (a mini-agent starts with one
   * greeting → count is always odd → `% N` is never 0). Process memory: after a restart the map is
   * empty, so the conversation's first turn is annotated again — acceptable and self-correcting.
   */
  private val lastAnnotatedCount = ConcurrentHashMap<String, Int>()

  /** The process scope (the class is an AppScope singleton): a background annotation outlives the screen and the turn itself. */
  private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** Conversations whose background annotation is already in flight — see [annotateInBackground]. */
  private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

  /**
   * [annotate] in a separate coroutine, OUTSIDE the turn. Orchestrators used to call it inside the
   * turn's flow, after Completed: the answer was already in the chat, but the turn (and its foreground
   * protection with a notification) lived on for one more model call, and the `runCatching` around it
   * swallowed cancellation — Stop at that moment did not stop the flow. Here Stop and a new turn do not
   * touch the annotation; a failure goes to [onFailure], cancellation is not swallowed.
   *
   * No more than one annotation per conversation at a time: two quick turns in a row would otherwise
   * both pass the rate gate (the [lastAnnotatedCount] anchor moves only after the model answers) and
   * pay twice.
   */
  fun annotateInBackground(conversationId: String, onFailure: (Throwable) -> Unit = {}) {
    if (!inFlight.add(conversationId)) return
    backgroundScope.launch {
      try {
        annotate(conversationId)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        onFailure(e)
      } finally {
        inFlight.remove(conversationId)
      }
    }
  }

  /** Regenerate title/summary/tags for [conversationId] from its current messages. */
  suspend fun annotate(conversationId: String) {
    // A counter instead of loading the whole history (it used to be getAllMessages on every turn).
    val count = conversationRepository.countAnnotatableMessages(conversationId)
    if (count < LlmContextConfig.MIN_MESSAGES_FOR_ANNOTATION) return
    // The rate gate: the annotation is a SECOND LLM call after the answer. The first time (last == null)
    // as soon as there are enough messages, then no more often than once per ANNOTATE_EVERY_MESSAGES
    // NEW messages since the last annotation. "Messages since the last one", not `count % N`: the latter
    // stopped firing forever on an odd count (see lastAnnotatedCount).
    val last = lastAnnotatedCount[conversationId]
    if (last != null && count - last < LlmContextConfig.ANNOTATE_EVERY_MESSAGES) return
    // A lesson chat is not annotated at all: its title is the lesson id (the lesson is found by it on
    // re-entry and prompts/roadmap/<title>.md is loaded by it). A question in the middle of a lesson
    // started a normal turn, the annotation rewrote the title with the topic of the conversation — and
    // the lesson broke the next time it was opened. A lesson needs no summary and tags either (it does
    // not appear in the chat list), and we save an LLM call.
    if (conversationRepository.getConversation(conversationId)?.isTutorial == true) return

    val messages = conversationRepository.getRecentAnnotatableMessages(
      conversationId,
      LlmContextConfig.ANNOTATION_MESSAGE_LIMIT,
    )
    val transcript = ContextWindowBuilder.formatMessagesForSummary(messages)
    // The gate's anchor moves only on SUCCESS: on an LLM failure (request == null) last is not updated,
    // and the next turn retries instead of "eating" the interval with silence.
    val annotation = request(transcript) ?: return
    conversationRepository.updateAnnotation(
      id = conversationId,
      title = annotation.title,
      summary = annotation.summary?.take(LlmContextConfig.SUMMARY_MAX_CHARS),
      tags = annotation.tags.orEmpty().take(LlmContextConfig.MAX_TAGS),
    )
    lastAnnotatedCount[conversationId] = count
  }

  private suspend fun request(transcript: String): Annotation? {
    val userContent = buildString {
      appendLine("Диалог пользователя и ассистента:")
      appendLine(transcript)
      appendLine()
      appendLine("Верни СТРОГО JSON-объект без markdown и пояснений вида:")
      appendLine("""{"title": "...", "summary": "...", "tags": ["...", "..."]}""")
      // The language is the interface language: the title is shown in the chat list next to translated captions.
      val lang = tr("на русском", "in English")
      appendLine("- title: короткий заголовок темы, до 6 слов, $lang.")
      appendLine("- summary: суть диалога (ключевые факты, решения, открытые задачи), до 500 слов, $lang.")
      appendLine("- tags: 2–5 коротких тегов в нижнем регистре (одно-два слова), $lang.")
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
    llmClient.streamChat(chat, tools = emptyList(), provider = selectedProvider()).collect { event ->
      when (event) {
        // Thinking instead of an answer is not an answer: a JSON draft from it would parse as a title.
        is StreamEvent.TextDelta -> if (!event.reasoning) builder.append(event.content)
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
