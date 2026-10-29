package com.aiopenpocket.agent.loop

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
// @trace approval-outcome-honesty-554a8c

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
import com.aiopenpocket.agent.core.policy.ApprovalOutcome
import com.aiopenpocket.agent.core.policy.ApprovalResultText
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ExecutionPolicy
import com.aiopenpocket.agent.core.policy.PolicyDecision
import com.aiopenpocket.agent.core.policy.ToolApprovalCoordinator
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.core.prompt.UntrustedFence
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
 * Simulate (PREVIEW mode), Deny, or AwaitApproval (suspends on [ToolApprovalCoordinator]). In LIVE
 * every DESTRUCTIVE/EXTERNAL call lands on AwaitApproval even with no hooks at all (the owner's
 * floor, see [ExecutionPolicy.evaluate]) — so plain chat can raise the approval bar too, and a
 * background run (`interactiveApproval = false`) denies such calls outright.
 *
 * Task 7 (Structured Plan Engine) adds one more, orthogonal per-run budget: `maxToolCalls` caps real
 * tool-dispatch attempts across the whole run, independent of [maxIterations] — see that parameter's
 * own kdoc. [com.aiopenpocket.agent.core.plan.execution.PlanExecutor] is its first caller, passing
 * each step's own [com.aiopenpocket.agent.core.plan.execution.PlanRunBudget]; every pre-Task-7 caller
 * is unaffected by the default [Int.MAX_VALUE].
 */
@SingleIn(AppScope::class)
@Inject
class ReactLoopRunner(
  private val llmClient: LlmClient,
  private val toolExecutor: ToolExecutor,
  private val toolRegistry: ToolRegistry,
  private val memoryRepository: MemoryRepository,
  private val log: AgentLog,
  private val agentHookRepository: AgentHookRepository,
  // No default value ON PURPOSE: `= ToolApprovalCoordinator()` would create a private instance if DI
  // ever failed to supply the shared singleton — then the UI would resolve approvals in one object
  // while the loop waited on another. The failure would be quiet, not loud: every confirmation
  // request would just hang until its timeout and come back unanswered. Better that it does
  // not compile.
  private val toolApprovalCoordinator: ToolApprovalCoordinator,
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
    /**
     * Hard cap on real tool-dispatch attempts across the WHOLE run (every iteration combined, not
     * per iteration) — Task 7's per-step [com.aiopenpocket.agent.core.plan.execution.PlanRunBudget
     * .maxToolCallsPerRun]. Once exhausted, every further tool_call (the one that tipped it over, any
     * sibling call still pending in the same assistant message, and any call in a later iteration)
     * gets a synthetic "not executed" [ToolResult.Error] instead of reaching [gateAndExecute] — same
     * protocol-preserving treatment as a hook [HookDecision.stopped] mid-loop (every tool_call still
     * gets an answering tool-result so the transcript stays a valid assistant/tool pair sequence) —
     * and the run stops, exactly like exhausting [maxIterations]. Default [Int.MAX_VALUE] is a strict
     * no-op for every pre-Task-7 caller.
     */
    maxToolCalls: Int = Int.MAX_VALUE,
    emit: suspend (AgentEvent) -> Unit,
  ): AgentResult {
    val toolTrace = mutableListOf<ToolTrace>()
    var accumulatedText = ""
    // The text of all iterations in a row — so that at the iteration limit we return a NON-empty answer
    // (the last iteration is often just a tool_call without text). On a normal finish we return the
    // last iteration's result as is.
    val transcript = StringBuilder()
    var finished = false
    var lastIteration = 0
    // Per-RUN (across all iterations) real-dispatch counter for maxToolCalls — see its own kdoc above.
    var toolCallsUsed = 0
    var toolBudgetExhausted = false
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
      // Text and tool calls accumulate within one iteration (one model turn). Reset at the start: the
      // preamble before a tool call must not stick to the next iteration's final answer.
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
              // Do not execute right away — collect all the calls of the turn to send them as ONE assistant
              // message with a tool_calls array (the protocol requires this for parallel calls).
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
      // A mini-agent's scope is implemented only through which schemas the model is shown (tools) —
      // ToolExecutor itself resolves a name against the GLOBAL ToolRegistry. Without this check a
      // model hallucination (or a tool name inherited from another agent's history) would execute even
      // if the tool was not in the current turn's scoped set.
      val allowedToolNames = tools.map { it.function.name }.toSet()
      // One assistant message with all the calls of the turn. The preamble goes into content so that on
      // the next iteration the model sees its own text that preceded the calls.
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

      // Keep this iteration's preamble in the overall transcript (in case we stop at the iteration limit).
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

        if (toolLoopStopped || toolBudgetExhausted) {
          val stoppedResult = ToolResult.Error(
            if (toolBudgetExhausted) {
              "Tool '${tc.name}' was not executed: this run's tool-call budget ($maxToolCalls) was already used."
            } else {
              "Tool '${tc.name}' was not executed: the run was stopped by an earlier hook this turn."
            },
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
          // Budget gate sits immediately before the real dispatch (plan's Task 7 Step 4: "Stop before
          // the next ToolExecutor.execute when budget is exhausted") — a call rejected by an EARLIER
          // branch above never counts against the budget at all.
          toolCallsUsed >= maxToolCalls -> {
            toolBudgetExhausted = true
            ToolResult.Error("Tool '${tc.name}' was not executed: this run's tool-call budget ($maxToolCalls) was already used.", recoverable = false)
          }
          else -> {
            toolCallsUsed++
            gateAndExecute(
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
        }
        log.d(TAG, "  ← ${tc.name}: ${preview(resultToString(result))}")
        toolTrace += ToolTrace(
          iteration = iteration,
          toolName = tc.name,
          arguments = tc.arguments,
          result = result,
        )
        emit(AgentEvent.ToolCompleted(tc.name, result, iteration, tc.arguments))

        // Episodic memory is a best-effort side effect: a write failure (DB busy/full) must not bring down
        // an otherwise successful turn.
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

        // Every tool_call must get exactly one tool response with the same id.
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

      if (toolLoopStopped || toolBudgetExhausted) break@runLoop
    }

    // Either the model answered without tools (finished), or we hit the step limit/tool-call budget —
    // in every case we return the accumulated text and trace; the caller picks the terminal event.
    if (toolBudgetExhausted) {
      log.w(TAG, "⚠ tool-call budget exhausted maxToolCalls=$maxToolCalls")
    } else if (!finished) {
      log.w(TAG, "⚠ exhausted maxIterations=$maxIterations")
    }
    // At the limit return the transcript of all iterations (not the possibly empty last text); on a finish — the result.
    val finalText = if (finished) accumulatedText else transcript.toString().trim().ifBlank { accumulatedText }
    fireHook(HookEvent.ON_COMPLETE, HookContext(iteration = lastIteration))
    // hitIterationLimit=!finished is the only "hit the limit" source for the GOAL fallback (a hook
    // STOP also lands here with hitIterationLimit=true: it halted before a model-driven finish, same
    // as exhausting maxIterations, so GOAL's existing partial-result fallback applies unchanged). A
    // tool-budget stop ALSO leaves finished=false (this iteration had pending tool calls, so it never
    // reached the finished=true branch), so hitIterationLimit is already true here too — stoppedByToolBudget
    // is the additional, precisely-named signal Task 7's PlanExecutor uses to tell budget kinds apart.
    return AgentResult(
      finalText, toolTrace, AgentState.FINISHED, lastIteration, null,
      hitIterationLimit = !finished, stoppedByToolBudget = toolBudgetExhausted,
    )
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
      is PolicyDecision.Simulate -> ToolResult.Success(simulatedResultJson(toolName, arguments), simulated = true)
      is PolicyDecision.Deny -> ToolResult.Error(decision.reason, recoverable = false)
      is PolicyDecision.AwaitApproval -> awaitApprovalAndExecute(decision.request, interactiveApproval, executionMode, emit)
    }
  }

  /**
   * A hook — or the LIVE floor for DESTRUCTIVE/EXTERNAL calls — required approval for [request]. A
   * non-interactive run ([interactiveApproval] false — a background heartbeat or plan step, no screen
   * could ever answer) denies immediately without ever registering with [toolApprovalCoordinator] —
   * no point publishing a request nobody will see and paying its timeout for nothing. Otherwise emits
   * [AgentEvent.ToolApprovalRequired] (so the chat UI can show a modal/bar, and [TurnManager] can push
   * a notification if the app is not on screen) and suspends on the coordinator; the coordinator's own
   * timeout ends the wait as [ApprovalOutcome.UNANSWERED], and "Stop" cancels this coroutine (the
   * coordinator's `finally` cleans up) — never a hang, never a silent execute.
   *
   * The three outcomes map to three different results, and that is the point of this function: a
   * timeout is NOT a denial, and the model is told so in words that contain no notion of refusal at
   * all (see [ApprovalResultText]). The app going to the background does not resolve the request
   * either: the turn keeps living in [TurnManager], which pushes "the agent needs your permission" so
   * there is something to come back to.
   *
   * Approval lifts the hook's question but does not cancel the preview: in [ExecutionMode.PREVIEW]
   * everything except reading is still only simulated after "Allow" — otherwise testing an agent draft
   * in the builder with a "Require confirmation" hook would really set alarms and write files, even
   * though the preview promises the opposite.
   */
  private suspend fun awaitApprovalAndExecute(
    request: ToolApprovalRequest,
    interactiveApproval: Boolean,
    executionMode: ExecutionMode,
    emit: suspend (AgentEvent) -> Unit,
  ): ToolResult {
    if (!interactiveApproval) {
      return ToolResult.Error(
        "Tool '${request.toolName}' requires the user's approval, which isn't available in a background run. " +
          "Do not retry; tell the user to do it from the chat.",
        recoverable = false,
      )
    }
    // Three outcomes, not a Boolean, and a separate text for each — the model cannot be more precise
    // than the fact it is given. A bare "was not approved" first read to it like a missing Android
    // permission (after "Decline" it told the user to enable mail in Settings); the fix spelled out
    // "declined it or did not answer in time", and the model resolved that "or" against the user:
    // a request that expired while the app was minimized came back as "Not sent — declined in chat."
    // The event goes out from inside awaitApproval, after the request is registered, never before it.
    return when (toolApprovalCoordinator.awaitApproval(request) { emit(AgentEvent.ToolApprovalRequired(request)) }) {
      ApprovalOutcome.APPROVED ->
        if (executionMode == ExecutionMode.PREVIEW && request.riskClass != RiskClass.READ) {
          ToolResult.Success(simulatedResultJson(request.toolName, request.arguments), simulated = true)
        } else {
          toolExecutor.execute(request.toolName, request.arguments)
        }
      ApprovalOutcome.DECLINED -> ToolResult.Error(ApprovalResultText.declined(request.toolName), recoverable = true)
      ApprovalOutcome.UNANSWERED -> ToolResult.Error(ApprovalResultText.unanswered(request.toolName), recoverable = true)
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
   * external content (files, calendar entries) is fenced in a `<tool_output trust="untrusted">`
   * envelope so any instructions embedded in it are read as data, never commands — the same
   * treatment `<user_data>` gets in the system prompt. Enforced in the harness, not in prompt text.
   */
  private fun toolMessageContent(toolName: String, result: ToolResult): String {
    val raw = resultToString(result)
    val untrusted = result is ToolResult.Success && toolRegistry.get(toolName)?.returnsUntrustedContent == true
    if (!untrusted) return raw
    // Neutralize any envelope tags inside the payload itself, so attacker-supplied content (e.g. a
    // file body containing a literal </tool_output>) cannot close the fence early and smuggle
    // instructions outside it. Shared with PrePromptAssembler's <user_data> fence: the invariant is
    // one, the places obliged to honour it are several, and the gap BETWEEN them was the actual hole.
    return UntrustedFence.wrap(
      tag = "tool_output",
      body = UntrustedFence.neutralize("tool_output", raw),
      attributes = "tool=\"$toolName\" trust=\"untrusted\"",
    )
  }

  /**
   * One-line, length-capped preview of args/results for the loop log (no newlines, no flooding).
   * Carries user data, so it only ever goes to [AgentLog.d] — which PlatformModule drops outside debug builds.
   */
  private fun preview(s: String): String =
    s.replace(Regex("\\s+"), " ").trim().let { if (it.length > 140) it.take(140) + "…" else it }

  companion object {
    private const val TAG = "ReactLoop"
    private const val LOAD_SKILL_TOOL = "load_skill"
    const val MAX_ITERATIONS = 10
  }
}
