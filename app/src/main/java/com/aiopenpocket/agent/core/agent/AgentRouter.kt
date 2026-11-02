package com.aiopenpocket.agent.core.agent

// @trace agent-router-dc2233

import com.aiopenpocket.agent.core.llm.AgentModel
import com.aiopenpocket.agent.core.llm.AgentModelContext
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.ReasoningEffort
import com.aiopenpocket.agent.core.llm.ReasoningEffortContext
import com.aiopenpocket.agent.core.llm.SelectedLlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who handles a message in a chat that is not bound to an agent (the home chat, the voice assistant).
 * [Auto] asks [AgentRouter]; [Fixed] is the person's own pick; [None] is the plain assistant.
 */
sealed interface RouteChoice {
  data object Auto : RouteChoice
  data object None : RouteChoice
  data class Fixed(val agentId: String) : RouteChoice
}

/**
 * The agent picked for ONE turn of an unbound chat. It rides the coroutine context, like
 * [AgentModelContext] and [ReasoningEffortContext], down to `PrePromptAssembler`, which then treats the
 * turn exactly as a turn of a chat bound to that agent: its role, tools, skills and memory. The
 * conversation itself stays unbound, so the next message is routed again.
 */
class RoutedAgentContext(val agentId: String) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<RoutedAgentContext>
}

/**
 * The picker's choice per conversation. The chat screen writes it; the turn reads it when it starts, so a
 * retry and a turn that finishes after the screen is gone see the same choice. Held in memory only: a
 * chat reopened later starts on [RouteChoice.Auto] again.
 */
@Inject
@SingleIn(AppScope::class)
class AgentRouteChoices {
  private val choices = ConcurrentHashMap<String, RouteChoice>()

  fun get(conversationId: String): RouteChoice = choices[conversationId] ?: RouteChoice.Auto

  fun set(conversationId: String, choice: RouteChoice) {
    choices[conversationId] = choice
  }
}

/**
 * Picks which of the person's agents should answer a message, or none. One short call to Muse Spark at
 * low reasoning effort, no tools: the model sees each agent's name, description and tools and names one
 * id. Anything it says that is not one of those ids means "no agent", and so does a failure or a slow
 * answer: routing must never be the reason a message goes unanswered.
 */
@Inject
@SingleIn(AppScope::class)
class AgentRouter(
  private val llmClient: LlmClient,
  private val selectedProvider: SelectedLlmProvider,
  private val agentResolver: AgentResolver,
) {

  /** The agent that should take [message] under [choice], or null for the plain assistant. */
  suspend fun pick(message: String, choice: RouteChoice): AgentDefinition? = when (choice) {
    RouteChoice.None -> null
    is RouteChoice.Fixed -> agentResolver.resolve(choice.agentId)?.takeIf { it.enabled }
    RouteChoice.Auto -> route(message, agentResolver.observeRoster().first().filter { it.enabled })
  }

  private suspend fun route(message: String, candidates: List<AgentDefinition>): AgentDefinition? {
    if (candidates.isEmpty() || message.isBlank()) return null
    val raw = try {
      withTimeoutOrNull(ROUTE_TIMEOUT_MS) { ask(message, candidates) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    } ?: return null
    val id = parseRoute(raw, candidates.map { it.id }.toSet()) ?: return null
    return candidates.firstOrNull { it.id == id }
  }

  private suspend fun ask(message: String, candidates: List<AgentDefinition>): String? {
    val chat = listOf(
      ChatMessage(role = "system", content = routerSystemPrompt(candidates)),
      ChatMessage(role = "user", content = message),
    )
    val answer = StringBuilder()
    var failed = false
    // Spark, not the app's model: picking from a short list needs speed, not depth.
    withContext(ReasoningEffortContext(ReasoningEffort.LOW) + AgentModelContext(AgentModel.SPARK.key)) {
      llmClient.streamChat(chat, tools = emptyList(), provider = selectedProvider()).collect { event ->
        when (event) {
          is StreamEvent.TextDelta -> if (!event.reasoning) answer.append(event.content)
          is StreamEvent.Error -> failed = true
          else -> Unit
        }
      }
    }
    return if (failed) null else answer.toString()
  }

  companion object {
    /** Past this the person is better served by the plain assistant than by a longer wait. */
    const val ROUTE_TIMEOUT_MS = 8_000L
  }
}

/** What the router model reads: the agents to choose from and the one-line answer it must give. */
internal fun routerSystemPrompt(candidates: List<AgentDefinition>): String = buildString {
  appendLine("You route a person's message to one of their AI agents, or to none.")
  appendLine("Agents:")
  candidates.forEach { agent ->
    append("- id: ").append(agent.id)
    append(" | name: ").append(agent.displayName)
    append(" | does: ").append(agent.displayDescription.replace('\n', ' '))
    if (agent.toolNames.isNotEmpty()) append(" | tools: ").append(agent.toolNames.sorted().joinToString(", "))
    appendLine()
  }
  appendLine()
  appendLine("Pick the agent whose job clearly covers the message. If none clearly fits — small talk, a")
  appendLine("general question, something outside every agent's job — answer none.")
  append("Reply with the id only, or the word none. No other text.")
}

/**
 * The id the model named, if it is one of [ids]. Tolerates quotes, a trailing dot or a short sentence
 * around the id; "none", an unknown id or two different ids all mean no agent.
 */
internal fun parseRoute(raw: String, ids: Set<String>): String? {
  val tokens = raw.lowercase().split(Regex("[^\\p{L}\\p{N}_-]+")).filter { it.isNotEmpty() }
  val named = ids.filter { id -> id.lowercase() in tokens }.distinct()
  return named.singleOrNull()
}
