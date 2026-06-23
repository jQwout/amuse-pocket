package com.aiopenpocket.agent.loop

import android.util.Log
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Single-turn chat agent: assembles the prompt context, then runs the shared [ReactLoopRunner] over
 * the full tool set and emits a terminal Completed/Error. The reasoning loop itself lives in
 * [ReactLoopRunner] so that [com.aiopenpocket.agent.loop.PlanExecuteOrchestrator] can reuse the exact
 * same engine per plan step.
 */
@SingleIn(AppScope::class)
class AgentLoopOrchestrator @Inject constructor(
  private val prePromptAssembler: PrePromptAssembler,
  private val conversationAnnotator: ConversationAnnotator,
  private val registry: ToolRegistry,
  private val reactLoopRunner: ReactLoopRunner,
) {

  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider = LlmProvider.NIM,
  ): Flow<AgentEvent> = channelFlow {
    val context = prePromptAssembler.assemble(conversationId, userMessage)
    val messages = mutableListOf<ChatMessage>()
    messages += ChatMessage(role = "system", content = context.systemPrompt)
    messages += context.history.map { it.toChatMessage() }
    // The caller persists the user message to DB before calling execute(),
    // so it's already included in context.history via ContextWindowBuilder.

    // Mini-agent chats run with only their scoped tools; a general chat gets the full set.
    val tools = context.toolNames?.let { registry.getSchemasFor(it) } ?: registry.getAllSchemas()
    Log.d(TAG, "▶ chat turn conv=$conversationId scoped=${context.toolNames ?: "all"}")
    val result = reactLoopRunner.run(conversationId, messages, tools, provider) { send(it) }

    if (result.state == AgentState.ERROR) {
      Log.w(TAG, "✗ turn error: ${result.errorMessage}")
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(result.errorMessage ?: "Unknown error"))
    } else {
      Log.d(TAG, "✓ turn done iters=${result.iterations} textLen=${result.text.length}")
      send(AgentEvent.StateChanged(AgentState.FINISHED))
      send(AgentEvent.Completed(result))
    }
    // After each reply, refresh the chat's title/summary/tags from the raw log (no-op if too short).
    runCatching { conversationAnnotator.annotate(conversationId) }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

  // История прошлых ходов реплеится как обычный текст user/assistant. Вызовы инструментов прошлых
  // ходов в чат-историю не пишутся (они живут в эпизодической памяти), поэтому tool_calls здесь не
  // восстанавливаем — внутри текущего хода они уже добавлены в messages напрямую.
  private fun PrePromptAssembler.PromptMessage.toChatMessage(): ChatMessage =
    ChatMessage(role = role, content = content)

  companion object {
    private const val TAG = "AgentLoop"
  }
}

enum class AgentState { IDLE, STREAMING, EXECUTING_TOOL, FINISHED, ERROR }

data class AgentResult(
  val text: String,
  val toolTrace: List<ToolTrace>,
  val state: AgentState,
  val iterations: Int,
  /** Set when [state] is ERROR; the message to surface to the user. */
  val errorMessage: String? = null,
)

data class ToolTrace(
  val iteration: Int,
  val toolName: String,
  val arguments: Map<String, kotlinx.serialization.json.JsonElement>,
  val result: ToolResult,
  val timestamp: Long = System.currentTimeMillis(),
)

sealed class AgentEvent {
  data class TextDelta(val content: String) : AgentEvent()
  data class ToolStarted(val name: String, val args: Map<String, kotlinx.serialization.json.JsonElement>) : AgentEvent()
  data class ToolCompleted(
    val name: String,
    val result: ToolResult,
    val iteration: Int,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement>,
  ) : AgentEvent()
  data class StateChanged(val state: AgentState) : AgentEvent()
  data class Completed(val result: AgentResult) : AgentEvent()
  data class Error(val message: String) : AgentEvent()
}
