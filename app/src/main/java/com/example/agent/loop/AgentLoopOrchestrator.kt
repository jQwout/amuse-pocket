package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@SingleIn(AppScope::class)
class AgentLoopOrchestrator @Inject constructor(
  private val llmClient: LlmClient,
  private val toolExecutor: ToolExecutor,
  private val prePromptAssembler: PrePromptAssembler,
  private val conversationAnnotator: ConversationAnnotator,
  private val registry: ToolRegistry,
  private val memoryRepository: MemoryRepository,
) {

  companion object {
    const val MAX_ITERATIONS = 10
  }

  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider = LlmProvider.AUTO,
  ): Flow<AgentEvent> = channelFlow {
    val context = prePromptAssembler.assemble(conversationId, userMessage)
    // The caller persists the user message to DB before calling execute(),
    // so it's already included in context.history via ContextWindowBuilder.
    val messages = mutableListOf(ChatMessage(role = "system", content = context.systemPrompt))
    messages += context.history.map { it.toChatMessage() }

    val tools = registry.getAllSchemas()
    val toolTrace = mutableListOf<ToolTrace>()
    var done = false

    for (iteration in 1..MAX_ITERATIONS) {
      if (done) break
      var toolCalledThisIteration = false
      // Текст копится в рамках одной итерации (одного хода модели). Финальный ответ —
      // это последняя итерация, поэтому преамбла перед tool-call не должна склеиваться с ним.
      var accumulatedText = ""
      send(AgentEvent.StateChanged(AgentState.STREAMING))

      llmClient.streamChat(messages, tools, provider).collect { chunk ->
        if (done) return@collect
        when (chunk) {
          is StreamEvent.TextDelta -> {
            accumulatedText += chunk.content
            send(AgentEvent.TextDelta(chunk.content))
          }
          is StreamEvent.ToolCall -> {
            toolCalledThisIteration = true
            toolTrace += handleToolCall(chunk, iteration, conversationId, messages)
          }
          is StreamEvent.Finish -> {
            // tool_calls finish → не завершаем: на следующей итерации модель получит вывод
            // инструментов и сформирует финальный ответ.
            if (chunk.reason != "tool_calls" && !toolCalledThisIteration) {
              send(AgentEvent.StateChanged(AgentState.FINISHED))
              send(AgentEvent.Completed(AgentResult(accumulatedText, toolTrace.toList(), AgentState.FINISHED, iteration)))
              done = true
            }
          }
          is StreamEvent.Error -> {
            send(AgentEvent.StateChanged(AgentState.ERROR))
            send(AgentEvent.Error(chunk.message))
            done = true
          }
        }
      }
    }
    if (!done) {
      send(AgentEvent.Error("Max iterations ($MAX_ITERATIONS) reached"))
    } else {
      // After each completed reply, refresh the chat's title/summary/tags from the raw log.
      runCatching { conversationAnnotator.annotate(conversationId) }
    }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

  private suspend fun ProducerScope<AgentEvent>.handleToolCall(
    chunk: StreamEvent.ToolCall,
    iteration: Int,
    conversationId: String,
    messages: MutableList<ChatMessage>,
  ): ToolTrace {
    send(AgentEvent.StateChanged(AgentState.EXECUTING_TOOL))
    send(AgentEvent.ToolStarted(chunk.name, chunk.arguments))

    val result = toolExecutor.execute(chunk.name, chunk.arguments)
    val trace = ToolTrace(iteration, chunk.name, chunk.arguments, result)
    send(AgentEvent.ToolCompleted(chunk.name, result, iteration, chunk.arguments))

    memoryRepository.saveEpisode(
      type = "tool_call",
      payload = buildJsonObject {
        put("id", chunk.id)
        put("name", chunk.name)
        put("arguments", JsonObject(chunk.arguments))
      }.toString(),
      outcome = result.toString(),
      conversationId = conversationId,
    )

    messages += ChatMessage(
      role = "assistant",
      toolCalls = listOf(
        ChatMessage.ToolCallData(
          id = chunk.id,
          function = ChatMessage.ToolCallData.FunctionCall(
            name = chunk.name,
            arguments = Json.encodeToString(chunk.arguments),
          ),
        ),
      ),
    )
    messages += ChatMessage(
      role = "tool",
      toolCallId = chunk.id,
      content = result.asMessageContent(),
    )
    return trace
  }

  private fun ToolResult.asMessageContent(): String = when (this) {
    is ToolResult.Success -> data
    is ToolResult.Error -> "Error: $message"
  }

  private fun PrePromptAssembler.PromptMessage.toChatMessage(): ChatMessage =
    ChatMessage(
      role = role,
      content = content,
      toolCalls = toolCallsJson?.let { json ->
        runCatching { Json.decodeFromString<List<ChatMessage.ToolCallData>>(json) }.getOrNull()
      },
    )
}

enum class AgentState { IDLE, STREAMING, EXECUTING_TOOL, FINISHED, ERROR }

data class AgentResult(
  val text: String,
  val toolTrace: List<ToolTrace>,
  val state: AgentState,
  val iterations: Int,
)

data class ToolTrace(
  val iteration: Int,
  val toolName: String,
  val arguments: Map<String, JsonElement>,
  val result: ToolResult,
  val timestamp: Long = System.currentTimeMillis(),
)

sealed class AgentEvent {
  data class TextDelta(val content: String) : AgentEvent()
  data class ToolStarted(val name: String, val args: Map<String, JsonElement>) : AgentEvent()
  data class ToolCompleted(
    val name: String,
    val result: ToolResult,
    val iteration: Int,
    val arguments: Map<String, JsonElement>,
  ) : AgentEvent()
  data class StateChanged(val state: AgentState) : AgentEvent()
  data class Completed(val result: AgentResult) : AgentEvent()
  data class Error(val message: String) : AgentEvent()
}
