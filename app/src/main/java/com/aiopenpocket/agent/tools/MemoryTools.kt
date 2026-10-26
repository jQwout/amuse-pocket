package com.aiopenpocket.agent.tools

// @trace mcp-tools-f77d9b
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.prompt.EpisodeFormat
import com.aiopenpocket.agent.loop.TurnContextHolder
import kotlinx.serialization.json.JsonElement
import dev.zacsweers.metro.Inject
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.SingleIn
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Inject
@SingleIn(AppScope::class)
class MemoryTools(
  private val memoryRepository: MemoryRepository,
  private val turnContextHolder: TurnContextHolder,
) : Tool {

  /**
   * Memory is an untrusted source, even though the table is our own: the model reads external text
   * (a file, the calendar) and puts it here through `remember_fact`, after which
   * `search_memory`/`recall_episodes` hand it back. Without this flag memory was a side channel around
   * the `<tool_output trust="untrusted">` envelope: FileTools/CalendarTool mark their output, while "the
   * same text, but via a fact" reached the model without the fence.
   */
  override val returnsUntrustedContent = true

  // Per-call risk (Task 9): remember_fact writes; search_memory/recall_episodes only read. An
  // unrecognized/missing action fails closed to READ - execute() rejects it before anything runs.
  override fun riskFor(arguments: Map<String, JsonElement>): RiskClass = when (arguments.string("action")) {
    "remember_fact" -> RiskClass.WRITE
    else -> RiskClass.READ
  }

  override val name = "memory_tool"
  override val description = "Управление долговременной памятью агента: запоминать факты, искать факты, вспоминать эпизоды."
  override val schema = toolSchema(name, description) {
    string("action", "Действие: remember_fact, search_memory, recall_episodes", required = true,
      enum = listOf("remember_fact", "search_memory", "recall_episodes"))
    string("key", "Ключ факта (для remember_fact)")
    string("value", "Значение факта (для remember_fact)")
    string("category", "Категория факта: profile, preference (для remember_fact)")
    string("query", "Запрос для поиска фактов (для search_memory)")
    string("range", "Диапазон для эпизодов (действий в этом чате): today, yesterday, last_week (для recall_episodes)")
  }

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val action = arguments.string("action") ?: return missingArg("action")
    return when (action) {
      "remember_fact" -> rememberFact(arguments)
      "search_memory" -> searchMemory(arguments)
      "recall_episodes" -> recallEpisodes(arguments)
      else -> ToolResult.Error("Unknown action: $action")
    }
  }

  private suspend fun rememberFact(args: Map<String, JsonElement>): ToolResult {
    val key = args.string("key") ?: return missingArg("key")
    val value = args.string("value") ?: return missingArg("value")
    // The agent's memory isolation lives IN CODE, not in a prompt instruction (Task 5): while an agent
    // with its own namespace is bound to the turn, the key ALWAYS goes under that namespace, whatever
    // the model asks for. Without a bound agent (a plain chat) the behavior does not change one bit —
    // that is the ONLY path that must stay global.
    val agent = turnContextHolder.agent
    val storedKey = agent?.memoryPolicy?.namespace?.plus(key) ?: key
    // For a plain chat the category is set by the user (via the model) — untrusted input clamped to an
    // allowlist. For an agent with its own namespace the category is FORCED to general:
    // profile/preference is the user's shared profile, which an agent may only read (see
    // PrePromptAssembler.includeSharedProfile), not append to through remember_fact.
    val category = if (agent != null) "general" else sanitizeCategory(args.string("category"))
    // upsertFact, NOT saveFact: a repeated remember_fact with the same key must UPDATE the fact.
    // saveFact inserts a row with a new UUID, and the schema has no uniqueness on `key` — "my name is
    // X", then "actually Y" left both in memory, and a contradiction went into the prompt (see
    // upsertFact).
    val id = memoryRepository.upsertFact(storedKey, value, category)
      ?: return ToolResult.Success("Fact cleared: $storedKey")
    return ToolResult.Success("Fact saved: $storedKey = $value (id=$id)")
  }

  /** The only categories the model may assign to facts. Anything else becomes general. */
  private fun sanitizeCategory(raw: String?): String =
    when (raw?.trim()?.lowercase()) {
      "profile" -> "profile"
      "preference" -> "preference"
      else -> "general"
    }

  private suspend fun searchMemory(args: Map<String, JsonElement>): ToolResult {
    val query = args.string("query") ?: return missingArg("query")
    // The same gate as in rememberFact: with a bound agent the search does not leave its namespace — at
    // the DAO/WHERE level (MemoryRepository.searchFactsInNamespace), not by post-filtering the model's
    // results. A plain chat (agent == null) searches globally, as before.
    val namespace = turnContextHolder.agent?.memoryPolicy?.namespace
    val facts = if (namespace != null) {
      memoryRepository.searchFactsInNamespace(namespace, query)
    } else {
      memoryRepository.searchFacts(query)
    }
    return if (facts.isEmpty()) {
      ToolResult.Success("No facts found for '$query'")
    } else {
      ToolResult.Success(facts.joinToString("\n") { "- ${it.key}: ${it.value} [${it.category}]" })
    }
  }

  /**
   * The tool call log of the CURRENT conversation within the [range] window. It used to be the log of
   * all conversations and all agents, whole and without a ceiling: the budget agent saw file reads from
   * other chats, and one read file (up to 40k characters) came back verbatim. Now it is the own
   * conversation (an agent in its chat sees its history, a plain chat sees its own), no more than
   * [EPISODES_MAX] events, each truncated the same way as in the "Recent actions" of the system prompt
   * ([EpisodeFormat]).
   */
  private suspend fun recallEpisodes(args: Map<String, JsonElement>): ToolResult {
    val range = args.string("range")
    // Without a conversation (a call outside a turn) return nothing rather than "everything": the scope must be closed.
    val conversationId = turnContextHolder.conversationId
      ?: return ToolResult.Success("No episodes found for range '${range ?: "default"}'")
    val dayMillis = 24L * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    val startOfToday = startOfTodayMillis()
    val (since, until) = when (range) {
      "today" -> startOfToday to Long.MAX_VALUE
      // Yesterday is the calendar window [start of yesterday, start of today), not the last 24 hours.
      "yesterday" -> (startOfToday - dayMillis) to startOfToday
      "last_week" -> (now - 7 * dayMillis) to Long.MAX_VALUE
      else -> (now - dayMillis) to Long.MAX_VALUE
    }
    val episodes = memoryRepository.getEpisodesForConversationBetween(conversationId, since, until, EPISODES_MAX)
    return if (episodes.isEmpty()) {
      ToolResult.Success("No episodes found for range '${range ?: "default"}'")
    } else {
      val timeFormat = SimpleDateFormat("dd.MM HH:mm", Locale.ROOT)
      ToolResult.Success(
        episodes.joinToString("\n") { "- ${timeFormat.format(Date(it.timestamp))} ${EpisodeFormat.line(it)}" },
      )
    }
  }

  private companion object {
    /** Ceiling of events in one `recall_episodes` answer: beyond that it is a log, not memory. */
    const val EPISODES_MAX = 20
  }
}
