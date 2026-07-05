package com.aiopenpocket.agent.loop

import android.util.Log
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * The core ReAct loop (reason → act → observe). It streams [AgentEvent]s through [emit] and RETURNS
 * the final [AgentResult] instead of emitting a terminal Completed/Error — the caller decides what a
 * finished loop means (a whole chat reply, or one step of a plan). Reused by [AgentLoopOrchestrator]
 * for the plain chat turn, by [PlanExecuteOrchestrator] for each plan step, and by
 * [SupervisorOrchestrator] for each specialist sub-pass. (The Supervisor's *coordinator* loop is
 * hand-rolled, because its `delegate` calls are fulfilled by the orchestrator rather than by
 * ToolExecutor.)
 */
@SingleIn(AppScope::class)
class ReactLoopRunner @Inject constructor(
  private val llmClient: LlmClient,
  private val toolExecutor: ToolExecutor,
  private val toolRegistry: ToolRegistry,
  private val memoryRepository: MemoryRepository,
) {

  /**
   * Drives [messages] through the tool-calling loop until the model answers without tools (or the
   * iteration budget runs out). [messages] is mutated in place with the assistant tool-call and
   * tool-result turns, so the caller can inspect or reuse the final transcript. Streaming deltas go
   * to [emit]; the terminal outcome is the return value.
   */
  suspend fun run(
    conversationId: String,
    messages: MutableList<ChatMessage>,
    tools: List<FunctionSchema>,
    provider: LlmProvider,
    emit: suspend (AgentEvent) -> Unit,
  ): AgentResult {
    val toolTrace = mutableListOf<ToolTrace>()
    var accumulatedText = ""
    var finished = false
    var lastIteration = 0
    Log.d(TAG, "▶ run conv=$conversationId tools=${tools.size} [${tools.joinToString { it.function.name }}]")

    for (iteration in 1..MAX_ITERATIONS) {
      lastIteration = iteration
      // Текст и tool-call копятся в рамках одной итерации (одного хода модели). Сбрасываем в начале:
      // преамбула перед tool-call не должна склеиваться с финальным ответом следующей итерации.
      accumulatedText = ""
      val pendingToolCalls = mutableListOf<StreamEvent.ToolCall>()
      var streamError: String? = null
      emit(AgentEvent.StateChanged(AgentState.STREAMING))

      llmClient.streamChat(messages, tools, provider).collect { chunk ->
        when (chunk) {
          is StreamEvent.TextDelta -> {
            accumulatedText += chunk.content
            emit(AgentEvent.TextDelta(chunk.content))
          }
          is StreamEvent.ToolCall -> {
            // Не выполняем сразу — копим все вызовы хода, чтобы оформить их ОДНИМ assistant-сообщением
            // с массивом tool_calls (этого требует протокол при нескольких параллельных вызовах).
            pendingToolCalls += chunk
          }
          is StreamEvent.Finish -> if (pendingToolCalls.isEmpty()) finished = true
          is StreamEvent.Error -> streamError = chunk.message
        }
      }

      if (streamError != null) {
        Log.w(TAG, "✗ iter $iteration stream error: $streamError")
        return AgentResult(accumulatedText, toolTrace, AgentState.ERROR, lastIteration, streamError)
      }
      if (finished) {
        Log.d(TAG, "✓ finished at iter $iteration (textLen=${accumulatedText.length})")
        break
      }
      if (pendingToolCalls.isEmpty()) continue

      Log.d(TAG, "iter $iteration: ${pendingToolCalls.size} tool-call(s) [${pendingToolCalls.joinToString { it.name }}]")
      emit(AgentEvent.StateChanged(AgentState.EXECUTING_TOOL))
      // Одно assistant-сообщение со всеми вызовами хода. Преамбулу кладём в content, чтобы на следующей
      // итерации модель видела собственный текст, предшествовавший вызовам.
      messages += ChatMessage(
        role = "assistant",
        content = accumulatedText.ifBlank { null },
        toolCalls = pendingToolCalls.map { tc ->
          ChatMessage.ToolCallData(
            id = tc.id,
            function = ChatMessage.ToolCallData.FunctionCall(
              name = tc.name,
              arguments = Json.encodeToString(tc.arguments),
            ),
          )
        },
      )

      for (tc in pendingToolCalls) {
        Log.d(TAG, "  → ${tc.name}(${preview(tc.arguments.toString())})")
        emit(AgentEvent.ToolStarted(tc.name, tc.arguments))

        val result = toolExecutor.execute(tc.name, tc.arguments)
        Log.d(TAG, "  ← ${tc.name}: ${preview(resultToString(result))}")
        toolTrace += ToolTrace(
          iteration = iteration,
          toolName = tc.name,
          arguments = tc.arguments,
          result = result,
        )
        emit(AgentEvent.ToolCompleted(tc.name, result, iteration, tc.arguments))

        // Эпизодическая память — побочный эффект «по возможности»: сбой записи (БД занята/полна) не
        // должен ронять уже успешный ход. runCatching, как и для annotate() в AgentLoopOrchestrator.
        runCatching {
          memoryRepository.saveEpisode(
            type = "tool_call",
            payload = buildJsonObject {
              put("id", tc.id)
              put("name", tc.name)
              // Record the tool's runtime risk class so side effects are auditable in memory.
              put("risk", (toolRegistry.get(tc.name)?.riskClass ?: com.aiopenpocket.agent.core.mcp.RiskClass.READ).name)
              put("arguments", JsonObject(tc.arguments))
            }.toString(),
            outcome = result.toString(),
            conversationId = conversationId,
          )
        }.onFailure { Log.w(TAG, "saveEpisode failed for ${tc.name}: ${it.message}") }

        // Каждый tool_call должен получить ровно один tool-ответ с тем же id.
        messages += ChatMessage(
          role = "tool",
          toolCallId = tc.id,
          content = toolMessageContent(tc.name, result),
        )
      }
    }

    // Либо модель ответила без инструментов (finished), либо упёрлись в лимит шагов — в обоих случаях
    // отдаём накопленный текст и трейс; терминальное событие выберет вызывающий.
    if (!finished) Log.w(TAG, "⚠ exhausted MAX_ITERATIONS=$MAX_ITERATIONS")
    return AgentResult(accumulatedText, toolTrace, AgentState.FINISHED, lastIteration, null)
  }

  private fun resultToString(result: ToolResult): String = when (result) {
    is ToolResult.Success -> result.data
    is ToolResult.Error -> "Error: ${result.message}"
  }

  /**
   * Builds the tool-result message fed back to the model. Output from tools that return untrusted
   * external content (SMS, files, calendar entries) is fenced in a `<tool_output trust="untrusted">`
   * envelope so any instructions embedded in it are read as data, never commands — the same
   * treatment `<user_data>` gets in the system prompt. Enforced in the harness, not in prompt text.
   */
  private fun toolMessageContent(toolName: String, result: ToolResult): String {
    val raw = resultToString(result)
    val untrusted = result is ToolResult.Success && toolRegistry.get(toolName)?.returnsUntrustedContent == true
    return if (untrusted) "<tool_output tool=\"$toolName\" trust=\"untrusted\">\n$raw\n</tool_output>" else raw
  }

  /** One-line, length-capped preview of args/results for the loop log (no newlines, no flooding). */
  private fun preview(s: String): String =
    s.replace(Regex("\\s+"), " ").trim().let { if (it.length > 140) it.take(140) + "…" else it }

  companion object {
    private const val TAG = "ReactLoop"
    const val MAX_ITERATIONS = 10
  }
}
