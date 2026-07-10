package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361

import com.aiopenpocket.agent.core.AgentLog
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * The core ReAct loop (reason → act → observe). It streams [AgentEvent]s through [emit] and RETURNS
 * the final [AgentResult] instead of emitting a terminal Completed/Error — the caller decides what a
 * finished loop means (a whole chat reply, or one phase of a multi-phase orchestrator). Used by
 * [AgentLoopOrchestrator] for the plain chat turn and by [ReflectionOrchestrator] for draft/revise.
 */
@SingleIn(AppScope::class)
class ReactLoopRunner @Inject constructor(
  private val llmClient: LlmClient,
  private val toolExecutor: ToolExecutor,
  private val toolRegistry: ToolRegistry,
  private val memoryRepository: MemoryRepository,
  private val log: AgentLog,
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
    maxIterations: Int = MAX_ITERATIONS,
    /** Mini-agent bound to this chat, if any — `load_skill` is confined to this agent's own skill. */
    boundAgentId: String? = null,
    emit: suspend (AgentEvent) -> Unit,
  ): AgentResult {
    val toolTrace = mutableListOf<ToolTrace>()
    var accumulatedText = ""
    // Текст всех витков подряд — чтобы на лимите витков вернуть НЕпустой ответ (последний виток часто =
    // только tool_call без текста). На нормальном финише возвращаем итог последнего витка как есть.
    val transcript = StringBuilder()
    var finished = false
    var lastIteration = 0
    log.d(TAG, "▶ run conv=$conversationId tools=${tools.size} [${tools.joinToString { it.function.name }}]")

    for (iteration in 1..maxIterations) {
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
        log.w(TAG, "✗ iter $iteration stream error: $streamError")
        return AgentResult(accumulatedText, toolTrace, AgentState.ERROR, lastIteration, streamError)
      }
      if (finished) {
        log.d(TAG, "✓ finished at iter $iteration (textLen=${accumulatedText.length})")
        break
      }
      if (pendingToolCalls.isEmpty()) continue

      log.d(TAG, "iter $iteration: ${pendingToolCalls.size} tool-call(s) [${pendingToolCalls.joinToString { it.name }}]")
      emit(AgentEvent.StateChanged(AgentState.EXECUTING_TOOL))
      // Скоуп мини-агента реализован только через то, какие schemas показаны модели (tools) —
      // ToolExecutor сам по себе резолвит имя против ГЛОБАЛЬНОГО ToolRegistry. Без этой проверки
      // галлюцинация модели (или унаследованное из истории имя тула от другого агента) выполнилась
      // бы, даже если этот tool не входил в scoped-набор текущего хода.
      val allowedToolNames = tools.map { it.function.name }.toSet()
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

      // Сохраняем преамбулу этого витка в общий транскрипт (на случай остановки по лимиту витков).
      if (accumulatedText.isNotBlank()) {
        if (transcript.isNotEmpty()) transcript.append("\n\n")
        transcript.append(accumulatedText)
      }

      for (tc in pendingToolCalls) {
        log.d(TAG, "  → ${tc.name}(${preview(tc.arguments.toString())})")
        emit(AgentEvent.ToolStarted(tc.name, tc.arguments))

        val result = if (tc.name !in allowedToolNames) {
          ToolResult.Error("Tool '${tc.name}' is not available in this context.", recoverable = false)
        } else if (tc.name == LOAD_SKILL_TOOL && !isSkillAllowed(tc.arguments, boundAgentId)) {
          ToolResult.Error("Skill not available for this agent")
        } else {
          toolExecutor.execute(tc.name, tc.arguments)
        }
        log.d(TAG, "  ← ${tc.name}: ${preview(resultToString(result))}")
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
        }.onFailure { log.w(TAG, "saveEpisode failed for ${tc.name}: ${it.message}") }

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
    if (!finished) log.w(TAG, "⚠ exhausted maxIterations=$maxIterations")
    // На лимите отдаём транскрипт всех витков (а не возможно-пустой текст последнего); на финише — итог.
    val finalText = if (finished) accumulatedText else transcript.toString().trim().ifBlank { accumulatedText }
    return AgentResult(finalText, toolTrace, AgentState.FINISHED, lastIteration, null)
  }

  private fun resultToString(result: ToolResult): String = when (result) {
    is ToolResult.Success -> result.data
    is ToolResult.Error -> "Error: ${result.message}"
  }

  /**
   * A mini-agent may only load its own skill (its [MiniAgent.id] doubles as that skill's id) —
   * otherwise a chat scoped to e.g. "budget" could load_skill("diary") and pull another domain's
   * instructions into context. No bound agent (plain chat) is unrestricted. A missing/malformed
   * `id` argument is let through so [ToolExecutor] surfaces its own "Missing 'id'" error.
   */
  private fun isSkillAllowed(arguments: Map<String, kotlinx.serialization.json.JsonElement>, boundAgentId: String?): Boolean {
    if (boundAgentId == null) return true
    val requestedId = (arguments["id"] as? JsonPrimitive)?.content ?: return true
    return requestedId == boundAgentId
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
    if (!untrusted) return raw
    // Neutralize any envelope tags inside the payload itself, so attacker-supplied content (e.g. an
    // SMS body containing a literal </tool_output>) cannot close the fence early and smuggle
    // instructions outside it. Turns <tool_output / </tool_output into <_tool_output / </_tool_output.
    val fenced = raw.replace(Regex("(?i)<(/?)tool_output"), "<$1_tool_output")
    return "<tool_output tool=\"$toolName\" trust=\"untrusted\">\n$fenced\n</tool_output>"
  }

  /** One-line, length-capped preview of args/results for the loop log (no newlines, no flooding). */
  private fun preview(s: String): String =
    s.replace(Regex("\\s+"), " ").trim().let { if (it.length > 140) it.take(140) + "…" else it }

  companion object {
    private const val TAG = "ReactLoop"
    private const val LOAD_SKILL_TOOL = "load_skill"
    const val MAX_ITERATIONS = 10
  }
}
