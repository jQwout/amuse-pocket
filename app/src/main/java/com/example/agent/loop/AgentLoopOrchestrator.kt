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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

// @trace rezhimy-agenta-plan-i-goal-ee2361
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
    provider: LlmProvider = LlmProvider.NIM,
    mode: AgentMode = AgentMode.CHAT,
    planApproved: Boolean = false,
    goalMaxIterations: Int = 3,
  ): Flow<AgentEvent> = channelFlow {
    // Контракт режима (гейт план-мода / петля гоал-мода) — чистое решение, покрыто AgentModeTest.
    val turn = resolveTurnExecutionPlan(mode, planApproved, goalMaxIterations, MAX_ITERATIONS)
    val maxIterations = turn.maxIterations

    val context = prePromptAssembler.assemble(conversationId, userMessage, turn.promptMode)
    // В гоал-моде сообщаем модели бюджет витков — иначе goal.md обещает «честно остановиться на лимите»,
    // но модель не знает, какой виток последний, и обычно уходит в обрыв на tool_call.
    val systemContent = if (mode == AgentMode.GOAL) {
      context.systemPrompt + "\n\nБюджет: не более " + maxIterations + " витков (рассуждение, действие, " +
        "самопроверка). На последнем витке дай финальный ответ или честный частичный итог — не начинай новых действий."
    } else {
      context.systemPrompt
    }
    val messages = mutableListOf<ChatMessage>()
    messages += ChatMessage(role = "system", content = systemContent)
    messages += context.history.map { it.toChatMessage() }
    // The caller persists the user message to DB before calling execute(),
    // so it's already included in context.history via ContextWindowBuilder.

    // Гейт: на фазе планирования инструменты не отдаём модели — она физически не может ничего выполнить.
    val tools = if (turn.usesTools) registry.getAllSchemas() else emptyList()
    val toolTrace = mutableListOf<ToolTrace>()
    var accumulatedText = ""
    // Текст всех витков подряд: при упоре в лимит финальный ответ не должен оказаться пустым —
    // последний виток часто = только tool_call без сопроводительного текста (паттерн «думаю→вызываю»).
    val transcript = StringBuilder()

    var done = false
    for (iteration in 1..maxIterations) {
      if (done) break
      // Текст и tool-call копятся в рамках одной итерации (одного хода модели). Сбрасываем в начале:
      // преамбула перед tool-call не должна склеиваться с финальным ответом следующей итерации.
      accumulatedText = ""
      val pendingToolCalls = mutableListOf<StreamEvent.ToolCall>()
      send(AgentEvent.StateChanged(AgentState.STREAMING))

      llmClient.streamChat(messages, tools, provider).collect { chunk ->
        if (done) return@collect
        when (chunk) {
          is StreamEvent.TextDelta -> {
            accumulatedText += chunk.content
            send(AgentEvent.TextDelta(chunk.content))
          }
          is StreamEvent.ToolCall -> {
            // Не выполняем сразу — копим все вызовы хода, чтобы оформить их ОДНИМ assistant-сообщением
            // с массивом tool_calls (этого требует протокол при нескольких параллельных вызовах).
            pendingToolCalls += chunk
          }
          is StreamEvent.Finish -> {
            if (pendingToolCalls.isEmpty()) {
              // Инструментов нет — это финальный ответ хода.
              send(AgentEvent.StateChanged(AgentState.FINISHED))
              send(
                AgentEvent.Completed(
                  AgentResult(
                    text = accumulatedText,
                    toolTrace = toolTrace,
                    state = AgentState.FINISHED,
                    iterations = iteration,
                  ),
                ),
              )
              done = true
            }
            // Иначе инструменты выполним после стрима (ниже) и уйдём на следующую итерацию.
          }
          is StreamEvent.Error -> {
            send(AgentEvent.StateChanged(AgentState.ERROR))
            send(AgentEvent.Error(chunk.message))
            done = true
          }
        }
      }

      if (done || pendingToolCalls.isEmpty()) continue

      send(AgentEvent.StateChanged(AgentState.EXECUTING_TOOL))
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

      // Сохраняем преамбулу этого витка в общий транскрипт (на случай остановки по лимиту).
      if (accumulatedText.isNotBlank()) {
        if (transcript.isNotEmpty()) transcript.append("\n\n")
        transcript.append(accumulatedText)
      }

      for (tc in pendingToolCalls) {
        send(AgentEvent.ToolStarted(tc.name, tc.arguments))

        val result = toolExecutor.execute(tc.name, tc.arguments)
        toolTrace += ToolTrace(
          iteration = iteration,
          toolName = tc.name,
          arguments = tc.arguments,
          result = result,
        )
        send(AgentEvent.ToolCompleted(tc.name, result, iteration, tc.arguments))

        memoryRepository.saveEpisode(
          type = "tool_call",
          payload = buildJsonObject {
            put("id", tc.id)
            put("name", tc.name)
            put("arguments", JsonObject(tc.arguments))
          }.toString(),
          outcome = result.toString(),
          conversationId = conversationId,
        )

        // Каждый tool_call должен получить ровно один tool-ответ с тем же id.
        messages += ChatMessage(
          role = "tool",
          toolCallId = tc.id,
          content = resultToString(result),
        )
      }
    }

    if (!done) {
      // Упёрлись в лимит витков (в гоал-моде это штатная честная остановка): отдаём накопленное
      // (текст + трейс), а не голую ошибку — работа не пропадает.
      send(AgentEvent.StateChanged(AgentState.FINISHED))
      send(
        AgentEvent.Completed(
          AgentResult(
            text = transcript.toString().trim()
              .ifBlank { "Достиг лимита витков — задача выполнена частично. Подробности — в трассировке инструментов." },
            toolTrace = toolTrace,
            state = AgentState.FINISHED,
            iterations = maxIterations,
          ),
        ),
      )
    }
    // After each reply, refresh the chat's title/summary/tags from the raw log (no-op if too short).
    runCatching { conversationAnnotator.annotate(conversationId) }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

  private fun resultToString(result: ToolResult): String = when (result) {
    is ToolResult.Success -> result.data
    is ToolResult.Error -> "Error: ${result.message}"
  }

  // История прошлых ходов реплеится как обычный текст user/assistant. Вызовы инструментов прошлых
  // ходов в чат-историю не пишутся (они живут в эпизодической памяти), поэтому tool_calls здесь не
  // восстанавливаем — внутри текущего хода они уже добавлены в messages напрямую.
  private fun PrePromptAssembler.PromptMessage.toChatMessage(): ChatMessage =
    ChatMessage(role = role, content = content)
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
