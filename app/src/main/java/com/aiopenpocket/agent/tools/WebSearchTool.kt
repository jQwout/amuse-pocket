package com.aiopenpocket.agent.tools

// @trace web-search-tool-c73ed8
// @trace mcp-tools-f77d9b

import com.aiopenpocket.agent.core.llm.SearchDepth
import com.aiopenpocket.agent.core.llm.WebSearchAnswer
import com.aiopenpocket.agent.core.llm.WebSearchClient
import com.aiopenpocket.agent.core.llm.WebSearchResult
import com.aiopenpocket.agent.core.llm.WebSource
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.missingArg
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.JsonElement

/**
 * Web search for agents: one call of Meta's hosted `web_search` and its answer with the pages it cited.
 *
 * The main agent loop talks to Meta through Chat Completions, where a hosted search tool does not
 * exist, so this tool is a bridge: it makes a separate Responses-API request ([WebSearchClient]) and
 * hands the agent the result as plain text. The result is untrusted content - it comes from web pages -
 * so the harness fences it like a file's text ([returnsUntrustedContent]).
 *
 * Risk: [com.aiopenpocket.agent.core.mcp.RiskClass.READ], the default. It changes nothing on the device
 * or in the person's data; the only thing a call costs is money ($2.50 per 1000 searches plus the
 * tokens, both written to the usage log by the client). That is why the description tells the model to
 * search only when it needs to.
 *
 * Never throws: a failure is a [ToolResult.Error] the model can read and move on from.
 */
@Inject
@SingleIn(AppScope::class)
class WebSearchTool(
  private val client: WebSearchClient,
) : Tool {

  override val name = NAME

  override val description =
    "Search the web for current or external facts (news, prices, schedules, documentation, anything after your " +
      "training data) and get a short answer with its sources. Each search costs money, so call it only when the " +
      "answer needs fresh or outside facts that you cannot give from memory or from the person's own data, and " +
      "do not repeat a search you already made. Cite the sources in your reply as markdown links."

  override val schema = toolSchema(name, description) {
    string("query", "What to look up: a focused question or keywords, in the language that fits the topic.", required = true)
    string(
      "depth",
      "How much to read: quick (one fact), normal (default), deep (a broader question; slower and costs more).",
      enum = listOf("quick", "normal", "deep"),
    )
  }

  /** Slightly above the client's own request timeout, so a slow answer ends with the client's clear text. */
  override val timeoutMs: Long = WebSearchClient.REQUEST_TIMEOUT_MS + 5_000L

  /** Web pages are not the app's text: injected instructions in them must stay data. */
  override val returnsUntrustedContent = true

  override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
    val query = arguments.string("query")?.trim() ?: return missingArg("query")
    val depth = SearchDepth.parse(arguments.string("depth"))
    return when (val result = client.search(query.take(MAX_QUERY_CHARS), depth)) {
      is WebSearchResult.Found -> ToolResult.Success(formatForModel(result.answer))
      is WebSearchResult.Failed -> ToolResult.Error(result.message)
    }
  }

  companion object {
    const val NAME = "web_search"
    private const val MAX_QUERY_CHARS = 500

    /** The result is bounded: it goes back into the agent's context for the rest of the turn. */
    internal const val MAX_RESULT_CHARS = 4000
    private const val MAX_SOURCES = 8
    private const val MAX_TITLE_CHARS = 120
    private const val MAX_URL_CHARS = 500

    /** The text for the model: the answer, a numbered list of the pages it cited, and how to cite them. */
    internal fun formatForModel(answer: WebSearchAnswer, maxChars: Int = MAX_RESULT_CHARS): String {
      var sources = answer.sources.filter { it.url.length <= MAX_URL_CHARS }.take(MAX_SOURCES)
      // The sources and the citing hint are kept whole; the answer text gives way to the limit first, and
      // only a source list so long that it would leave the answer no room loses its last entries.
      while (sources.isNotEmpty() && tailFor(sources).length > maxChars - MIN_TEXT_CHARS) sources = sources.dropLast(1)
      val tail = tailFor(sources)
      val room = (maxChars - tail.length).coerceAtLeast(MIN_TEXT_CHARS)
      val text = if (answer.text.length <= room) answer.text else answer.text.take(room - 1).trimEnd() + "…"
      return text + tail
    }

    private fun tailFor(sources: List<WebSource>): String =
      if (sources.isEmpty()) {
        // Said plainly so the agent does not present an unsourced answer as a checked one.
        "\n\nNo sources were cited for this answer."
      } else {
        sources.mapIndexed { i, s -> "[${i + 1}] ${s.title.take(MAX_TITLE_CHARS)} - ${s.url}" }.joinToString(
          separator = "\n",
          prefix = "\n\nSources:\n",
          postfix = "\n\nCite the sources in your reply as markdown links, e.g. [title](url).",
        )
      }

    private const val MIN_TEXT_CHARS = 500
  }
}
