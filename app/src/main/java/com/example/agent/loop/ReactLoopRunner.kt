package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.hook.AgentHookRunEntity
import com.aiopenpocket.agent.core.hook.HookContext
import com.aiopenpocket.agent.core.hook.HookDecision
import com.aiopenpocket.agent.core.hook.HookEngine
import com.aiopenpocket.agent.core.hook.HookEvent
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolExecutor
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ExecutionPolicy
import com.aiopenpocket.agent.core.policy.PolicyDecision
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import java.util.UUID

/**
 * The core ReAct loop (reason → act → observe). It streams [AgentEvent]s through [emit] and RETURNS
 * the final [AgentResult] instead of emitting a terminal Completed/Error — the caller decides what a
 * finished loop means (a whole chat reply, or one phase of a multi-phase orchestrator). Used by
 * [AgentLoopOrchestrator] for the plain chat turn and by [ReflectionOrchestrator] for draft/revise.
 *
 * When [run] is called with a bound [AgentDefinition] (`agent != null`) that carries
 * [AgentDefinition.hooks], the declarative [HookEngine] (Task 8) fires at all six lifecycle points
 * around this loop: `BEFORE_TURN`/`AFTER_TURN` around each [LlmClient.streamChat] call, `BEFORE_TOOL`/
 * `AFTER_TOOL` around each tool execution, `ON_ERROR` before the error return, `ON_COMPLETE` before
 * the successful return. Hooks never call the LLM or a tool themselves — every effect (denying a
 * tool, injecting an ephemeral instruction, halting the run, journaling to `agent_hook_runs`) is
 * applied here. Plain chat (`agent == null`) is untouched: hooks are looked up from the immutable
 * per-turn snapshot ([AgentDefinition.hooks]), never re-queried from Room mid-turn.
 *
 * Task 9 adds one more gate between a tool_call and [ToolExecutor]: [ExecutionPolicy] combines the
 * BEFORE_TOOL [HookDecision] above with the turn's [ExecutionMode] and the tool's per-call
 * [RiskClass] ([com.aiopenpocket.agent.core.mcp.Tool.riskFor]) into one [PolicyDecision] — Execute,
 * Simulate (PREVIEW mode), Deny, or AwaitApproval (suspends on [ToolApprovalCoordinator]).
 */
@SingleIn(AppScope::class)
class ReactLoopRunner @Inject constructor(
  private val llmClient: LlmClient,
  private val toolExecutor: ToolExecutor,
  private val toolRegistry: ToolRegistry,
  private val memoryRepository: MemoryRepository,
  private val log: AgentLog,
  private val agentHookRepository: AgentHookRepository,
  private val toolApprovalCoordinator: ToolApprovalCoordinator = ToolApprovalCoordinator(),
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
    /** Agent bound to this chat, if any — `load_skill` is confined to this agent's own [AgentDefinition.skillIds]. */
    agent: AgentDefinition? = null,
    /** LIVE (default) executes tools for real; PREVIEW simulates non-READ calls — see [ExecutionPolicy]. */
    executionMode: ExecutionMode = ExecutionMode.LIVE,
    /**
     * False for a headless/background run with no screen that could ever answer an approval prompt
     * (e.g. [com.aiopenpocket.agent.workers.HeartbeatRunner]) — an AwaitApproval decision becomes an
     * immediate deny instead of registering with [ToolApprovalCoordinator] and waiting out its
     * timeout for no one. True (default) for every TurnManager-driven interactive chat turn, where a
     * screen might really be open — and if it isn't, the coordinator's own timeout still applies.
     */
    interactiveApproval: Boolean = true,
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

    // --- Hook lifecycle plumbing (Task 8) ------------------------------------------------------
    // hooks comes from the immutable per-turn AgentDefinition snapshot, never re-queried from Room
    // mid-turn. Empty for agent == null (plain chat) AND for any agent with no hooks configured —
    // fireHook short-circuits before touching HookEngine or the journal repository in either case,
    // so this is a strict no-op addition for every existing (non-agent or hook-less) caller.
    val hooks = agent?.hooks.orEmpty()
    val runId = UUID.randomUUID().toString()
    var hookExecutions = 0

    suspend fun fireHook(event: HookEvent, context: HookContext): HookDecision {
      if (hooks.isEmpty()) return HookDecision()
      val firings = HookEngine.matched(hooks, event, context, hookExecutions)
      hookExecutions += firings.size
      for (firing in firings) {
        // Journaling is a best-effort side effect, same treatment as saveEpisode below — a DB write
        // failure must not fail an otherwise-successful turn.
        runCatching {
          agentHookRepository.recordRun(
            AgentHookRunEntity(
              id = UUID.randomUUID().toString(),
              hookId = firing.hookId,
              conversationId = conversationId,
              runId = runId,
              timestamp = System.currentTimeMillis(),
              status = firing.actionType.name,
              summary = firing.detail,
              errorMessage = firing.error,
            ),
          )
        }.onFailure { log.w(TAG, "hook journal write failed for ${firing.hookId}: ${it.message}") }
      }
      return HookEngine.toDecision(firings)
    }

    runLoop@ for (iteration in 1..maxIterations) {
      lastIteration = iteration
      // Текст и tool-call копятся в рамках одной итерации (одного хода модели). Сбрасываем в начале:
      // преамбула перед tool-call не должна склеиваться с финальным ответом следующей итерации.
      accumulatedText = ""
      val pendingToolCalls = mutableListOf<StreamEvent.ToolCall>()
      var streamError: String? = null
      emit(AgentEvent.StateChanged(AgentState.STREAMING))

      val beforeTurn = fireHook(HookEvent.BEFORE_TURN, HookContext(iteration = iteration))
      if (beforeTurn.stopped) {
        log.d(TAG, "⏹ BEFORE_TURN hook stopped the run at iter $iteration")
        break@runLoop
      }

      // ADD_INSTRUCTION: an ephemeral system message for THIS streamChat call only. Inserted/removed
      // at the HEAD (right after the base system message at index 0), NEVER the tail — the tail holds
      // assistant(tool_calls)/tool pairs from earlier iterations, and splitting a pair there would
      // give an HTTP 400 on strict providers.
      val ephemeralIndex = 1.coerceAtMost(messages.size)
      val ephemeralText = beforeTurn.addedInstructions.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
      if (ephemeralText != null) messages.add(ephemeralIndex, ChatMessage(role = "system", content = ephemeralText))
      try {
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
      } finally {
        if (ephemeralText != null) messages.removeAt(ephemeralIndex)
      }

      if (streamError != null) {
        log.w(TAG, "✗ iter $iteration stream error: $streamError")
        fireHook(HookEvent.ON_ERROR, HookContext(iteration = iteration, errorMessage = streamError))
        return AgentResult(accumulatedText, toolTrace, AgentState.ERROR, lastIteration, streamError)
      }

      val afterTurn = fireHook(HookEvent.AFTER_TURN, HookContext(iteration = iteration))
      if (afterTurn.stopped) {
        log.d(TAG, "⏹ AFTER_TURN hook stopped the run at iter $iteration")
        break@runLoop
      }

      if (finished) {
        log.d(TAG, "✓ finished at iter $iteration (textLen=${accumulatedText.length})")
        break@runLoop
      }
      if (pendingToolCalls.isEmpty()) continue@runLoop

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

      // Once a hook STOPs the run mid-tool-loop, every OTHER tool_call already in this same
      // assistant message still needs an answering tool-result (protocol pairing) — they just skip
      // execution and BEFORE_TOOL/AFTER_TOOL entirely instead of firing hooks for a call that was
      // never going to run.
      var toolLoopStopped = false
      for (tc in pendingToolCalls) {
        emit(AgentEvent.ToolStarted(tc.name, tc.arguments))

        if (toolLoopStopped) {
          val stoppedResult = ToolResult.Error(
            "Tool '${tc.name}' was not executed: the run was stopped by an earlier hook this turn.",
            recoverable = false,
          )
          toolTrace += ToolTrace(iteration = iteration, toolName = tc.name, arguments = tc.arguments, result = stoppedResult)
          emit(AgentEvent.ToolCompleted(tc.name, stoppedResult, iteration, tc.arguments))
          messages += ChatMessage(role = "tool", toolCallId = tc.id, content = toolMessageContent(tc.name, stoppedResult))
          continue
        }

        log.d(TAG, "  → ${tc.name}(${preview(tc.arguments.toString())})")

        val beforeTool = fireHook(HookEvent.BEFORE_TOOL, HookContext(iteration = iteration, toolName = tc.name))

        val result = when {
          tc.name !in allowedToolNames -> ToolResult.Error("Tool '${tc.name}' is not available in this context.", recoverable = false)
          tc.name == LOAD_SKILL_TOOL && !isSkillAllowed(tc.arguments, agent) -> ToolResult.Error("Skill not available for this agent")
          // STOP halts the whole run regardless of this call's own risk/mode — checked ahead of the
          // policy gate so a STOP+PREVIEW combination reports "stopped", not a misleading Simulate.
          beforeTool.stopped -> ToolResult.Error("Tool '${tc.name}' was not executed: an agent hook stopped the run.", recoverable = false)
          else -> gateAndExecute(
            toolName = tc.name,
            arguments = tc.arguments,
            hookDecision = beforeTool,
            executionMode = executionMode,
            interactiveApproval = interactiveApproval,
            runId = runId,
            conversationId = conversationId,
            emit = emit,
          )
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
              put("risk", (toolRegistry.get(tc.name)?.riskClass ?: RiskClass.READ).name)
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

        val afterTool = fireHook(
          HookEvent.AFTER_TOOL,
          HookContext(iteration = iteration, toolName = tc.name, toolSucceeded = result is ToolResult.Success),
        )
        if (beforeTool.stopped || afterTool.stopped) toolLoopStopped = true
      }

      if (toolLoopStopped) break@runLoop
    }

    // Либо модель ответила без инструментов (finished), либо упёрлись в лимит шагов — в обоих случаях
    // отдаём накопленный текст и трейс; терминальное событие выберет вызывающий.
    if (!finished) log.w(TAG, "⚠ exhausted maxIterations=$maxIterations")
    // На лимите отдаём транскрипт всех витков (а не возможно-пустой текст последнего); на финише — итог.
    val finalText = if (finished) accumulatedText else transcript.toString().trim().ifBlank { accumulatedText }
    fireHook(HookEvent.ON_COMPLETE, HookContext(iteration = lastIteration))
    // hitIterationLimit=!finished — единственный источник «упёрлись в лимит» для GOAL-фолбэка (a hook
    // STOP also lands here with hitIterationLimit=true: it halted before a model-driven finish, same
    // as exhausting maxIterations, so GOAL's existing partial-result fallback applies unchanged).
    return AgentResult(finalText, toolTrace, AgentState.FINISHED, lastIteration, null, hitIterationLimit = !finished)
  }

  private fun resultToString(result: ToolResult): String = when (result) {
    is ToolResult.Success -> result.data
    is ToolResult.Error -> "Error: ${result.message}"
  }

  /**
   * An agent may only load a skill listed in its own [AgentDefinition.skillIds] — built-in or its
   * own Room custom skill — otherwise a chat scoped to e.g. "budget" could load_skill("diary") and
   * pull another domain's (or another user agent's) instructions into context. No bound agent
   * (plain chat) is unrestricted. A missing/malformed `id` argument is let through so
   * [ToolExecutor] surfaces its own "Missing 'id'" error.
   */
  private fun isSkillAllowed(arguments: Map<String, JsonElement>, agent: AgentDefinition?): Boolean {
    if (agent == null) return true
    val requestedId = (arguments["id"] as? JsonPrimitive)?.content ?: return true
    return requestedId in agent.skillIds
  }

  /**
   * The gate between a hook-cleared tool_call and [ToolExecutor] (Task 9). Classifies this call's
   * actual risk ([com.aiopenpocket.agent.core.mcp.Tool.riskFor]), asks the pure [ExecutionPolicy]
   * what to do about it, and carries out exactly that: run for real, fabricate a Simulate envelope,
   * deny outright, or resolve an approval. [hookDecision] is the SAME [HookDecision] the caller
   * already computed for this call's `BEFORE_TOOL` firing — its denied/approvalRequired win over
   * [executionMode]/risk unconditionally (see [ExecutionPolicy.evaluate]'s own doc for why).
   */
  private suspend fun gateAndExecute(
    toolName: String,
    arguments: Map<String, JsonElement>,
    hookDecision: HookDecision,
    executionMode: ExecutionMode,
    interactiveApproval: Boolean,
    runId: String,
    conversationId: String,
    emit: suspend (AgentEvent) -> Unit,
  ): ToolResult {
    val risk = toolRegistry.get(toolName)?.riskFor(arguments) ?: RiskClass.READ
    val decision = ExecutionPolicy.evaluate(
      mode = executionMode,
      risk = risk,
      hookDecision = hookDecision,
      toolName = toolName,
      arguments = arguments,
      runId = runId,
      requestId = UUID.randomUUID().toString(),
      conversationId = conversationId,
    )
    return when (decision) {
      PolicyDecision.Execute -> toolExecutor.execute(toolName, arguments)
      is PolicyDecision.Simulate -> ToolResult.Success(simulatedResultJson(toolName, arguments))
      is PolicyDecision.Deny -> ToolResult.Error(decision.reason, recoverable = false)
      is PolicyDecision.AwaitApproval -> awaitApprovalAndExecute(decision.request, interactiveApproval, emit)
    }
  }

  /**
   * A hook required approval for [request]. A non-interactive run ([interactiveApproval] false — a
   * background heartbeat, no screen could ever answer) denies immediately without ever registering
   * with [toolApprovalCoordinator] — no point publishing a request nobody will see and paying its
   * timeout for nothing. Otherwise emits [AgentEvent.ToolApprovalRequired] (so the chat UI can show a
   * modal/bar) and suspends on the coordinator; its own timeout, or the app going to background,
   * both resolve to a deny — never a hang, never a silent execute.
   */
  private suspend fun awaitApprovalAndExecute(
    request: ToolApprovalRequest,
    interactiveApproval: Boolean,
    emit: suspend (AgentEvent) -> Unit,
  ): ToolResult {
    if (!interactiveApproval) {
      return ToolResult.Error(
        "Tool '${request.toolName}' requires approval, which isn't available in a background run.",
        recoverable = false,
      )
    }
    emit(AgentEvent.ToolApprovalRequired(request))
    val approved = toolApprovalCoordinator.awaitApproval(request)
    return if (approved) {
      toolExecutor.execute(request.toolName, request.arguments)
    } else {
      ToolResult.Error("Tool '${request.toolName}' was not approved.", recoverable = true)
    }
  }

  /** The synthetic result the model sees for a [PolicyDecision.Simulate] call — no real tool ever ran. */
  private fun simulatedResultJson(toolName: String, arguments: Map<String, JsonElement>): String =
    buildJsonObject {
      put("status", "preview_only")
      put("executed", false)
      put("tool", toolName)
      put("arguments", JsonObject(arguments))
    }.toString()

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
