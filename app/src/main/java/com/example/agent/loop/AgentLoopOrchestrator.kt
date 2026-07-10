package com.aiopenpocket.agent.loop

import android.util.Log
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Single-turn chat agent: assembles the prompt context, then runs the shared [ReactLoopRunner] over
 * the tool set and emits a terminal Completed/Error. The reasoning loop itself lives in
 * [ReactLoopRunner] so that multi-phase orchestrators ([ReflectionOrchestrator]) reuse the same engine.
 *
 * Поверх базового хода поддерживает режимы агента ([AgentMode]): план-гейт (фаза планирования без
 * инструментов → одобрение → выполнение) и гоал-петля (лимит витков). Решение режима — чистая
 * [resolveTurnExecutionPlan] (покрыта AgentModeTest).
 */
// @trace rezhimy-agenta-plan-i-goal-ee2361
@SingleIn(AppScope::class)
class AgentLoopOrchestrator @Inject constructor(
  private val prePromptAssembler: PrePromptAssembler,
  private val conversationAnnotator: ConversationAnnotator,
  private val registry: ToolRegistry,
  private val reactLoopRunner: ReactLoopRunner,
  private val turnContextHolder: TurnContextHolder,
) {

  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider = LlmProvider.NIM,
    mode: AgentMode = AgentMode.CHAT,
    planApproved: Boolean = false,
    goalMaxIterations: Int = 3,
  ): Flow<AgentEvent> = channelFlow {
    // Same error contract as the Supervisor/Plan-Execute orchestrators: a thrown error (assemble,
    // serialization, a DB write) becomes an in-band AgentEvent.Error instead of a raw flow throw.
    try {
      // Контракт режима (гейт план-мода / петля гоал-мода) — чистое решение, покрыто AgentModeTest.
      val turn = resolveTurnExecutionPlan(mode, planApproved, goalMaxIterations, ReactLoopRunner.MAX_ITERATIONS)

      val context = prePromptAssembler.assemble(conversationId, userMessage, turn.promptMode)
      // В гоал-моде сообщаем модели бюджет витков — иначе goal.md обещает «честно остановиться на лимите»,
      // но модель не знает, какой виток последний, и обычно уходит в обрыв на tool_call.
      val systemContent = if (mode == AgentMode.GOAL) {
        context.systemPrompt + "\n\nБюджет: не более " + turn.maxIterations + " витков (рассуждение, действие, " +
          "самопроверка). На последнем витке дай финальный ответ или честный частичный итог — не начинай новых действий."
      } else {
        context.systemPrompt
      }
      val messages = mutableListOf<ChatMessage>()
      messages += ChatMessage(role = "system", content = systemContent)
      messages += context.history.map { it.toChatMessage() }
      // The caller persists the user message to DB before calling execute(),
      // so it's already included in context.history via ContextWindowBuilder.

      // Гейт план-мода: на фазе планирования инструменты модели не отдаём (она физически ничего не
      // выполнит). Иначе — обычный набор: scoped для мини-агента, либо полный.
      val tools = if (!turn.usesTools) {
        emptyList()
      } else {
        context.toolNames?.let { registry.getSchemasFor(it) } ?: registry.getAllSchemas()
      }
      Log.d(TAG, "▶ chat turn conv=$conversationId mode=$mode scoped=${context.toolNames ?: "all"} maxIter=${turn.maxIterations}")
      val baseResult = withContext(turnContextHolder.contextElement(conversationId, context.agentId)) {
        reactLoopRunner.run(
          conversationId, messages, tools, provider, turn.maxIterations, context.agentId,
        ) { send(it) }
      }

      // Token estimates feed the chat's response stats bar; computed at the boundary so the
      // ReactLoopRunner stays unaware of UI concerns.
      val promptTokensEstimate = LlmContextConfig.estimateTokens(context.systemPrompt) +
        context.history.sumOf { LlmContextConfig.estimateTokens(it.content) }
      // Гоал-мод: на лимите витков не отдаём пустой ответ — честный частичный итог (трейс есть в UI).
      val finalText = if (mode == AgentMode.GOAL && baseResult.text.isBlank()) {
        "Достиг лимита витков — задача выполнена частично. Подробности — в трассировке инструментов."
      } else {
        baseResult.text
      }
      val result = baseResult.copy(
        text = finalText,
        promptTokensEstimate = promptTokensEstimate,
        completionTokensEstimate = LlmContextConfig.estimateTokens(finalText),
      )

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
    } catch (e: CancellationException) {
      // Stop / new turn: cancellation is the normal path — let it propagate to cancelAndJoin.
      throw e
    } catch (e: Exception) {
      Log.e(TAG, "✗ chat turn failed", e)
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(e.message ?: "Unknown error"))
    }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

  companion object {
    private const val TAG = "AgentLoop"
  }
}

enum class AgentState { IDLE, STREAMING, EXECUTING_TOOL, FINISHED, ERROR }

// История прошлых ходов реплеится как обычный текст user/assistant. Вызовы инструментов прошлых
// ходов в чат-историю не пишутся (они живут в эпизодической памяти), поэтому tool_calls здесь не
// восстанавливаем — внутри текущего хода они уже добавлены в messages напрямую. Общий хелпер для
// всех оркестраторов (чат / супервизор / план-исполнитель).
internal fun PrePromptAssembler.PromptMessage.toChatMessage(): ChatMessage =
  ChatMessage(role = role, content = content)

data class AgentResult(
  /**
   * The assistant message to persist. For the plain chat loop this is the model's final-turn text
   * (any preamble before tool calls is intentionally dropped — see ReactLoopRunner), except on an
   * iteration-limit stop where the full transcript is returned. For the Supervisor and Plan-Execute
   * orchestrators it is the full streamed transcript, so the saved message matches what the user watched.
   */
  val text: String,
  val toolTrace: List<ToolTrace>,
  val state: AgentState,
  val iterations: Int,
  /** Set when [state] is ERROR; the message to surface to the user. */
  val errorMessage: String? = null,
  val promptTokensEstimate: Int = 0,
  val completionTokensEstimate: Int = 0,
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
