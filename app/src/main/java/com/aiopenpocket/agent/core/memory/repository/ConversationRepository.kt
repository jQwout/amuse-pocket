package com.aiopenpocket.agent.core.memory.repository

// @trace multi-chat-conversations-b3c93b

import com.aiopenpocket.agent.core.memory.dao.ConversationDao
import com.aiopenpocket.agent.core.memory.dao.ConversationTagDao
import com.aiopenpocket.agent.core.memory.dao.MessageDao
import com.aiopenpocket.agent.core.memory.entity.ConversationEntity
import com.aiopenpocket.agent.core.memory.entity.ConversationTagEntity
import com.aiopenpocket.agent.core.memory.entity.MessageEntity
import com.aiopenpocket.agent.core.memory.model.Conversation
import com.aiopenpocket.agent.core.memory.model.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.UUID
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

@SingleIn(AppScope::class)
@Inject
class ConversationRepository(
  private val conversationDao: ConversationDao,
  private val conversationTagDao: ConversationTagDao,
  private val messageDao: MessageDao,
) {
  fun getAllConversations(): Flow<List<Conversation>> =
    combine(conversationDao.getAll(), conversationTagDao.getAll()) { conversations, tags ->
      val tagsByConversation = tags.groupBy({ it.conversationId }, { it.tag })
      conversations.map { it.toDomain(tagsByConversation[it.id].orEmpty()) }
    }

  suspend fun getConversation(id: String): Conversation? =
    conversationDao.getById(id)?.toDomain(conversationTagDao.getTagsForConversation(id))

  /**
   * The freshest chat bound to the mini-agent [agentId], or null. Needed so that entering an agent again
   * reopens its existing chat instead of spawning a new empty one on every tap.
   */
  suspend fun findLatestConversationByAgent(agentId: String): Conversation? {
    val entity = conversationDao.findLatestByAgent(agentId, excludedTag = BUILDER_TEST_TAG) ?: return null
    return entity.toDomain(conversationTagDao.getTagsForConversation(entity.id))
  }

  /**
   * The freshest plain (not an agent's, not a tutorial) chat, if it has NO messages YET. Needed so that
   * tapping "New chat" again without sending a message reuses the already created empty chat instead of
   * spawning new empty "New chat" entries in the conversation list on every tap.
   */
  suspend fun findReusableNewChat(): Conversation? {
    val latest = conversationDao.findLatestReusableNewChat() ?: return null
    val hasMessages = messageDao.getRecent(latest.id, limit = 1).isNotEmpty()
    if (hasMessages) return null
    return latest.toDomain(conversationTagDao.getTagsForConversation(latest.id))
  }

  /** The existing chat of lesson [title] (Roadmap/Library reuse it instead of creating a new one). */
  suspend fun findLatestTutorialConversation(title: String): Conversation? {
    val entity = conversationDao.findLatestTutorialByTitle(title) ?: return null
    return entity.toDomain(conversationTagDao.getTagsForConversation(entity.id))
  }

  suspend fun createConversation(
    title: String,
    personaId: String = "sharp_partner",
    isTutorial: Boolean = false,
    agentId: String? = null,
    tags: List<String> = emptyList(),
  ): String {
    val id = UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    conversationDao.insert(
      ConversationEntity(
        id = id,
        title = title,
        createdAt = now,
        updatedAt = now,
        personaId = personaId,
        isTutorial = isTutorial,
        agentId = agentId,
      ),
    )
    tags.forEach { conversationTagDao.insert(ConversationTagEntity(conversationId = id, tag = it)) }
    return id
  }

  suspend fun updateTitle(id: String, title: String) {
    conversationDao.updateTitle(id, title)
  }

  suspend fun updateSummary(id: String, summary: String) {
    conversationDao.updateSummary(id, summary)
  }

  /**
   * Apply the background annotation produced from the chat log: title, summary and tags. Each part
   * is applied only when non-blank, so a partial LLM result never wipes a good earlier value.
   */
  suspend fun updateAnnotation(id: String, title: String?, summary: String?, tags: List<String>) {
    title?.trim()?.takeIf { it.isNotBlank() }?.let { conversationDao.updateTitle(id, it) }
    summary?.trim()?.takeIf { it.isNotBlank() }?.let { conversationDao.updateSummary(id, it) }
    val normalized = tags.map(::normalizeTag).filter { it.isNotBlank() }.distinct()
    if (normalized.isNotEmpty()) {
      // System tags ("__…", e.g. BUILDER_TEST_TAG) are set by code, not by the annotator, and must not be
      // overwritten: without the marker the builder's test chat would pop up in the conversation list
      // after the very first annotation and be found as "the agent's chat".
      val reserved = conversationTagDao.getTagsForConversation(id).filter { it.startsWith(RESERVED_TAG_PREFIX) }
      conversationTagDao.replaceTags(id, (normalized + reserved).distinct().map { ConversationTagEntity(id, it) })
    }
  }

  suspend fun getAllTags(): List<String> = conversationTagDao.getAllTags()

  /** Conversation ids that share any tag with [tags], most-overlapping first. */
  suspend fun getConversationIdsByTags(tags: List<String>): List<String> {
    val normalized = tags.map(::normalizeTag).filter { it.isNotBlank() }.distinct()
    if (normalized.isEmpty()) return emptyList()
    return conversationTagDao.getConversationIdsByTags(normalized)
  }

  suspend fun deleteConversation(id: String) {
    conversationDao.delete(id)
  }

  /** Pins/unpins the conversation as a favorite (favorites rise to the top of the list). */
  suspend fun setFavorite(id: String, favorite: Boolean) {
    conversationDao.setFavorite(id, favorite)
  }

  /** Persists the lesson cursor on the conversation — independently of the message count. */
  suspend fun updateLessonCursor(id: String, stepIndex: Int, finished: Boolean) {
    conversationDao.updateLessonCursor(id, stepIndex, finished)
  }

  fun getMessages(conversationId: String): Flow<List<Message>> =
    messageDao.getByConversation(conversationId).map { list ->
      list.map { it.toDomain() }
    }.flowOn(Dispatchers.Default)

  /**
   * A window of the last [limit] messages in chronological order (old→new). The DB returns the freshest
   * in reverse order (DESC + LIMIT), so they are reversed. The chat uses it for pagination, so as not to
   * load and keep the whole history of a long conversation in memory. flowOn(Default): the reversal and
   * the mapping to the domain do not run in the collector's context (Main) — with large windows (search
   * over the whole history) it is noticeable work.
   */
  fun getMessagesWindow(conversationId: String, limit: Int): Flow<List<Message>> =
    messageDao.getByConversationWindow(conversationId, limit).map { list ->
      list.asReversed().map { it.toDomain() }
    }.flowOn(Dispatchers.Default)

  suspend fun getAllMessages(conversationId: String): List<Message> =
    messageDao.getAllOrdered(conversationId).map { it.toDomain() }

  /**
   * Head + tail + the count of "prompt" messages for assembling the prompt window (ContextWindowBuilder)
   * WITHOUT loading the whole history from disk on every turn. The "prompt" filter is on the SQL side.
   */
  suspend fun getPromptWindowParts(
    conversationId: String,
    headCount: Int,
    tailCount: Int,
  ): PromptWindowParts {
    val total = messageDao.countPromptable(conversationId)
    if (total <= headCount + tailCount) {
      val all = messageDao.getFirstPromptable(conversationId, total).map { it.toDomain() }
      return PromptWindowParts(head = all, tail = emptyList(), totalPromptable = total)
    }
    val head = messageDao.getFirstPromptable(conversationId, headCount).map { it.toDomain() }
    val tail = messageDao.getLastPromptable(conversationId, tailCount)
      .asReversed()
      .map { it.toDomain() }
    return PromptWindowParts(head = head, tail = tail, totalPromptable = total)
  }

  data class PromptWindowParts(
    val head: List<Message>,
    val tail: List<Message>,
    val totalPromptable: Int,
  )

  /** The count of text user/assistant messages — the rate gate of the background annotator. */
  suspend fun countAnnotatableMessages(conversationId: String): Int =
    messageDao.countAnnotatable(conversationId)

  /** The last [limit] text user/assistant messages (old→new) — the annotator's input. */
  suspend fun getRecentAnnotatableMessages(conversationId: String, limit: Int): List<Message> =
    messageDao.getLastAnnotatable(conversationId, limit).asReversed().map { it.toDomain() }

  suspend fun addMessage(
    conversationId: String,
    role: String,
    content: String,
    toolCallsJson: String? = null,
    cardSpec: String? = null,
  ): String {
    val id = UUID.randomUUID().toString()
    messageDao.insert(
      MessageEntity(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        cardSpec = cardSpec,
      ),
    )
    // A message is conversation activity: bump updatedAt so the "last chat" and the list order reflect real
    // freshness (otherwise updatedAt stayed at the creation/annotation time).
    conversationDao.touch(conversationId)
    return id
  }

  /** Deletes one message by id (a user action in the chat). */
  suspend fun deleteMessage(messageId: String) {
    messageDao.deleteById(messageId)
  }

  private fun ConversationEntity.toDomain(tags: List<String>) = Conversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    personaId = personaId,
    summary = summary,
    isTutorial = isTutorial,
    tags = tags,
    agentId = agentId,
    isFavorite = isFavorite,
    lessonStepIndex = lessonStepIndex,
    lessonFinished = lessonFinished,
  )

  private fun MessageEntity.toDomain() = Message(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    toolCallsJson = toolCallsJson,
    cardSpec = cardSpec,
    createdAt = createdAt,
  )

  companion object {
    private const val PLACEHOLDER_TITLE_MAX_CHARS = 40
    private const val DEFAULT_TITLE = "Новый диалог"

    /**
     * Marks the agent builder's throwaway test conversation. It is bound to the draft's `agentId`
     * (the TEST step has to run the real agent), which would otherwise make it a candidate for
     * [findLatestConversationByAgent] — so the first "Open chat" on a freshly built agent could
     * land the user inside the builder's scratch chat, complete with its test messages. Excluded by
     * tag rather than by title (titles are user-editable) and rather than by a new column (a schema
     * bump would destructively reset the local database before release).
     */
    const val BUILDER_TEST_TAG = "__builder_test"

    /** Prefix of the system tags that code sets: the annotator does not overwrite them (see updateAnnotation). */
    const val RESERVED_TAG_PREFIX = "__"

    /**
     * Title shown until the background annotator generates a real one: the first message,
     * clipped with an ellipsis. Falls back to a default when the message is blank.
     */
    fun placeholderTitle(firstMessage: String): String {
      val trimmed = firstMessage.trim()
      if (trimmed.isBlank()) return DEFAULT_TITLE
      return if (trimmed.length <= PLACEHOLDER_TITLE_MAX_CHARS) {
        trimmed
      } else {
        trimmed.take(PLACEHOLDER_TITLE_MAX_CHARS).trimEnd() + "…"
      }
    }

    /** Tags are single lowercase tokens; internal whitespace collapses to one hyphen. */
    fun normalizeTag(raw: String): String =
      raw.trim().lowercase().replace(Regex("\\s+"), "-").trim('-')
  }
}
