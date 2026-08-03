package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.plan.execution.PlanRunResult
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Single-turn chat agent: assembles the prompt context, then runs the shared [ReactLoopRunner] over
 * the tool set and emits a terminal Completed/Error. The reasoning loop itself lives in
 * [ReactLoopRunner] so that multi-phase orchestrators ([ReflectionOrchestrator]) reuse the same engine.
 *
 * Поверх базового хода поддерживает [AgentMode.TUTOR] (разовый вопрос без инструментов, один ход);
 * [AgentMode.CHAT] — обычный цикл. Решение режима — чистая [resolveTurnExecutionPlan] (покрыта
 * AgentModeTest).
 *
 * Task 10 (structured-plan-engine-907066): [com.aiopenpocket.di.TurnModule]'s `TurnExecutor` dispatches
 * [AgentMode.PLAN]/[AgentMode.GOAL] to [StructuredPlanOrchestrator] instead, which runs the typed
 * Structured Plan Engine ([com.aiopenpocket.agent.core.plan.execution.PlanRunner]/`PlanExecutor`/
 * `PlanVerifier`) — this class is called ONLY with [AgentMode.CHAT]/[AgentMode.TUTOR]. Task 15
 * (structured-plan-engine-907066) removed the old prompt-only PLAN/GOAL gate/loop this class used to
 * carry (`planApproved`/`goalMaxIterations` params, [resolveTurnExecutionPlan]'s PLAN/GOAL branches)
 * along with the `prompts/modes` assets it depended on — Structured Plan Engine is now the only
 * PLAN/GOAL implementation.
 */
// @trace rezhimy-agenta-plan-i-goal-ee2361
@SingleIn(AppScope::class)
@Inject
class AgentLoopOrchestrator(
  private val prePromptAssembler: PrePromptAssembler,
  private val conversationAnnotator: ConversationAnnotator,
  private val registry: ToolRegistry,
  private val reactLoopRunner: ReactLoopRunner,
  private val turnContextHolder: TurnContextHolder,
  private val log: AgentLog,
) {

  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider,
    mode: AgentMode = AgentMode.CHAT,
    systemPromptExtra: String? = null,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ): Flow<AgentEvent> = agentTurnFlow(log, TAG, "Unknown error") {
    // Error-контракт (in-band Error, rethrow отмены, buffer+IO) — в agentTurnFlow.
    // Контракт режима (CHAT/TUTOR) — чистое решение, покрыто AgentModeTest.
    val turn = resolveTurnExecutionPlan(mode, ReactLoopRunner.MAX_ITERATIONS)

    val context = prePromptAssembler.assemble(conversationId, userMessage)
    // Секция бюджета витков (и любая другая режимная добавка) собрана в resolveTurnExecutionPlan;
    // systemPromptExtra — доверенный контекст хода (напр. текст текущего шага урока для tutor-вопроса).
    val withSuffix = turn.systemPromptSuffix
      ?.let { context.systemPrompt + "\n\n" + it }
      ?: context.systemPrompt
    val systemContent = systemPromptExtra
      ?.takeIf { it.isNotBlank() }
      ?.let { withSuffix + "\n\n" + it }
      ?: withSuffix
    val messages = mutableListOf<ChatMessage>()
    messages += ChatMessage(role = "system", content = systemContent)
    messages += context.history.map { it.toChatMessage() }
    // The caller persists the user message to DB before calling execute(),
    // so it's already included in context.history via ContextWindowBuilder.

    // Гейт tutor-хода: модель только объясняет, инструменты не отдаём. Иначе — обычный набор:
    // scoped для мини-агента, либо полный.
    val tools = if (!turn.usesTools) {
      emptyList()
    } else {
      context.toolNames?.let { registry.getSchemasFor(it) } ?: registry.getAllSchemas()
    }
    log.d(TAG, "▶ chat turn conv=$conversationId mode=$mode scoped=${context.toolNames ?: "all"} maxIter=${turn.maxIterations}")
    val baseResult = withContext(turnContextHolder.contextElement(conversationId, context.agent)) {
      reactLoopRunner.run(
        conversationId, messages, tools, provider, turn.maxIterations, context.agent, executionMode,
      ) { send(it) }
    }

    // Token estimates feed the chat's response stats bar; computed at the boundary so the
    // ReactLoopRunner stays unaware of UI concerns.
    val promptTokensEstimate = LlmContextConfig.estimateTokens(context.systemPrompt) +
      context.history.sumOf { LlmContextConfig.estimateTokens(it.content) }
    val result = baseResult.copy(
      promptTokensEstimate = promptTokensEstimate,
      completionTokensEstimate = LlmContextConfig.estimateTokens(baseResult.text),
    )

    if (result.state == AgentState.ERROR) {
      log.w(TAG, "✗ turn error: ${result.errorMessage}")
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(result.errorMessage ?: "Unknown error"))
    } else {
      log.d(TAG, "✓ turn done iters=${result.iterations} textLen=${result.text.length}")
      send(AgentEvent.StateChanged(AgentState.FINISHED))
      send(AgentEvent.Completed(result))
    }
    // After each reply, refresh the chat's title/summary/tags from the raw log (no-op if too short).
    runCatching { conversationAnnotator.annotate(conversationId) }
  }

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
   * iteration-limit stop where the full transcript is returned. For [ReflectionOrchestrator] it is
   * the full streamed transcript, so the saved message matches what the user watched.
   */
  val text: String,
  val toolTrace: List<ToolTrace>,
  val state: AgentState,
  val iterations: Int,
  /** Set when [state] is ERROR; the message to surface to the user. */
  val errorMessage: String? = null,
  val promptTokensEstimate: Int = 0,
  val completionTokensEstimate: Int = 0,
  /**
   * Цикл остановился, упёршись в лимит витков (а не потому что модель дала финальный ответ).
   * [com.aiopenpocket.agent.core.plan.execution.PlanExecutor] читает это (вместе с
   * [stoppedByToolBudget]) как `stoppedByBudget`, чтобы решить, нужна ли верификация шага плана.
   */
  val hitIterationLimit: Boolean = false,
  /**
   * Task 7 (Structured Plan Engine): true when [ReactLoopRunner]'s `maxToolCalls` budget — not
   * `maxIterations` — is what actually stopped the run. [hitIterationLimit] is already `true` in
   * this case too (the loop never reached a model-driven finish), so every existing caller keyed on
   * [hitIterationLimit] is unaffected; this is the additional, precisely-named signal
   * [com.aiopenpocket.agent.core.plan.execution.PlanExecutor] uses to tell the two budget kinds
   * apart when it fills in [com.aiopenpocket.agent.core.plan.execution.PlanStepExecutionResult
   * .stoppedByBudget].
   */
  val stoppedByToolBudget: Boolean = false,
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
  /**
   * A `BEFORE_TOOL` hook required approval for this call (Task 9): [request] is what the chat UI
   * needs to show a modal/bar and what [com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator]
   * needs to route the user's decision back. Emitted right before
   * [com.aiopenpocket.agent.loop.ReactLoopRunner] suspends on the coordinator for this same request —
   * consumers that only need to know a decision is pending (not resolve it) can watch this event
   * instead of also depending on the coordinator directly.
   */
  data class ToolApprovalRequired(val request: ToolApprovalRequest) : AgentEvent()

  /**
   * Task 10 (structured-plan-engine-907066): a PLAN turn
   * ([com.aiopenpocket.agent.loop.StructuredPlanOrchestrator.propose]) persisted a new/revised
   * proposal — [planId]/[version] identify exactly the [com.aiopenpocket.agent.core.plan.model.PlanVersion]
   * `TurnManager` should describe in the assistant message it persists for this event.
   * [com.aiopenpocket.agent.core.plan.persistence.PlanRepository.saveProposal] has already run by the
   * time this is sent — [com.aiopenpocket.ui.chat.ChatViewModel]'s own reactive subscription to
   * `PlanRepository.observeByConversation` (not this event) is what populates
   * `com.aiopenpocket.ui.chat.ChatUiState.pendingPlanApproval`; this event only drives the persisted
   * chat message.
   */
  data class PlanProposed(val planId: String, val version: Int) : AgentEvent()

  /**
   * Task 10: a GOAL turn ([com.aiopenpocket.agent.loop.StructuredPlanOrchestrator.executeGoal]) ran
   * [com.aiopenpocket.agent.core.plan.execution.PlanRunner] for zero or more steps and stopped
   * (budget used up, nothing runnable/due right now, or the whole plan reached
   * [com.aiopenpocket.agent.core.plan.model.PlanStatus.COMPLETED]) without a hard failure. Wraps the
   * whole [PlanRunResult] (rather than flattening its fields here) so `TurnManager` can render every
   * outcome — [com.aiopenpocket.agent.core.plan.model.PlanRunOutcome.PARTIAL] included — without this
   * event needing its own copy of that type's fields.
   */
  data class PlanProgressed(val result: PlanRunResult) : AgentEvent()

  /**
   * Task 10: a PLAN/GOAL turn could not proceed at all and needs something from the user before it
   * can — the planner asked a clarifying question
   * ([com.aiopenpocket.agent.core.plan.planner.PlanProposalResult.NeedsInfo], no plan was persisted
   * for it), or GOAL found the conversation's plan paused/awaiting a different
   * approval/needing revision. Deliberately NOT [AgentEvent.Error]: this is an ordinary, expected
   * turn outcome (mirrors how the old `[[NEED_INFO]]` marker was never treated as an error either),
   * not an app/LLM failure — `TurnManager` persists [reason] as a plain assistant message, no
   * `canRetry` banner.
   */
  data class PlanBlocked(val planId: String, val reason: String) : AgentEvent()
}
