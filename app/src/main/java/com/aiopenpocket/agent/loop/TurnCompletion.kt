package com.aiopenpocket.agent.loop

// @trace streaming-llm-chat-6595a1
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.plan.execution.PlanRunResult
import com.aiopenpocket.agent.core.plan.execution.PlanStepOutput
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome
import com.aiopenpocket.agent.core.plan.model.StepStatus
import com.aiopenpocket.i18n.tr
import kotlinx.serialization.json.JsonElement

/**
 * Every user-facing text of the turn layer — in one place. Logic only REFERS to these, it never makes
 * up its own. The texts are getters with [tr], not consts: the interface language changes on the fly.
 */
object TurnTexts {
  val BLANK_REPLY_ERROR: String
    get() = tr("Модель вернула пустой ответ — попробуй ещё раз.", "The model returned an empty reply — try again.")

  /** Push preview when the turn finished without text (a card-only answer). */
  val TURN_DONE_FALLBACK: String get() = tr("Готово", "Done")

  /** The turn did not fit into the background protection window (shortService, ~3 min) while the app was minimized. */
  val PROTECTION_EXPIRED: String
    get() = tr(
      "Ход не уложился в три минуты фоновой работы и был остановлен системой. Нажми «Повторить».",
      "The turn didn't finish within three minutes of background work and the system stopped it. Tap “Retry”.",
    )

  /**
   * Progress in the stream bubble while the agent works with a tool between ReAct iterations. Without
   * it the previous iteration's preamble faded into nothing on the next iteration (resetStreaming) and
   * looked like "the answer is being written, then wiped". [TOOL_PROGRESS] is the generic one (before
   * the name is known), [toolProgress] carries the tool name.
   */
  val TOOL_PROGRESS: String get() = tr("Использую инструмент…", "Using a tool…")

  fun toolProgress(toolName: String, arguments: Map<String, JsonElement> = emptyMap()): String =
    tr("Использую инструмент: ", "Using a tool: ") + "${CapabilityNames.call(toolName, arguments)}…"

  /** The turn failed for a reason other than the model (a DB write, an unexpected exception): details go to the log. */
  val TURN_FAILED: String get() = tr("Что-то пошло не так — попробуй ещё раз.", "Something went wrong — try again.")

  /**
   * The tail of the answer when the loop hit the step limit and the model never gave a result.
   * Without it the iterations' preambles ("let me check…") went to the chat as if they were the
   * answer, and with empty preambles — an "empty answer". The hint "ask me to continue" is there
   * because continuing really works: the tool results are already in the conversation's episodic
   * memory.
   */
  val ITERATION_LIMIT_NOTE: String
    get() = tr("_Остановился на лимите шагов — попроси продолжить._", "_Stopped at the step limit — ask me to continue._")

  /**
   * Task 10 (structured-plan-engine-907066): PLAN turn persisted a proposal —
   * [com.aiopenpocket.ui.chat.ChatViewModel]'s own reactive subscription to
   * `PlanRepository.observeByConversation` is what actually raises the approval bar
   * (`ChatUiState.pendingPlanApproval`); this text is only the chat-history record of the event.
   */
  // The plan card sits ABOVE the input field, i.e. below this message — hence "below".
  fun planProposedMessage(planId: String, version: Int): String =
    tr(
      "📋 План предложен (версия $version). Проверь его в карточке ниже и одобри, чтобы начать выполнение.",
      "📋 Plan proposed (version $version). Review it in the card below and approve it to start.",
    )

  /**
   * Task 10: GOAL turn's [PlanRunResult] rendered as a plain assistant message — one branch per [PlanRunOutcome].
   * Steps are named by their titles ([PlanStepOutput.title]), not by ids: this used to show
   * "Steps done: collect_limits". The step's own answer is a separate message ([planStepOutputMessage]).
   */
  fun planProgressMessage(result: PlanRunResult): String = when (result.outcome) {
    PlanRunOutcome.COMPLETED -> tr("✅ План выполнен: все шаги завершены.", "✅ Plan complete: all steps are done.")
    PlanRunOutcome.PARTIAL -> {
      val done = completedStepsLine(result)
      if (done == null) {
        result.blockedReason ?: tr("Пока нет шагов, готовых к выполнению.", "No steps are ready to run yet.")
      } else {
        "$done ${result.blockedReason ?: tr("Продолжи, когда будешь готов.", "Continue when you're ready.")}"
      }
    }
    PlanRunOutcome.FAILED -> result.blockedReason ?: tr("Выполнение шага плана завершилось ошибкой.", "A plan step failed.")
    PlanRunOutcome.CANCELLED -> tr("Выполнение плана отменено.", "Plan cancelled.")
  }

  /** 'Step "X" done.' for the steps that reached COMPLETED in this run; null if there are none. */
  private fun completedStepsLine(result: PlanRunResult): String? {
    val titles = result.stepOutputs.filter { it.status == StepStatus.COMPLETED }
      .map { tr("«${it.title}»", "“${it.title}”") }
    return when (titles.size) {
      0 -> null
      1 -> tr("Шаг ${titles.single()} выполнен.", "Step ${titles.single()} is done.")
      else -> tr("Выполнены шаги: ${titles.joinToString()}.", "Steps done: ${titles.joinToString()}.")
    }
  }

  /**
   * An executed plan step's answer as an assistant message: a heading with the step's title (in the
   * background such messages arrive without a user message — without a heading it is unclear what
   * they belong to) and the text itself. An answer made only of cards stays without text — like a
   * card-only answer of a normal turn.
   */
  fun planStepOutputMessage(output: PlanStepOutput): String {
    val text = output.text.trim()
    return if (text.isEmpty()) "" else tr("**Шаг «${output.title}»**", "**Step “${output.title}”**") + "\n\n$text"
  }
}

/**
 * Writes the answers of executed plan steps ([PlanRunResult.stepOutputs]) to the chat: the text with
 * the first card, the remaining cards as separate messages, the way [TurnManager] does it for a normal
 * turn. Shared by the foreground turn (TurnManager, via TurnPersistence) and the background heartbeat
 * (HeartbeatRunner, via ConversationRepository) — they have different write ports, so the write comes
 * in as a lambda. Steps without text and cards are skipped. Returns the id of the last inserted
 * message.
 */
internal suspend fun persistPlanStepOutputs(
  outputs: List<PlanStepOutput>,
  addAssistantMessage: suspend (text: String, cardSpec: String?) -> String,
): String? {
  var lastId: String? = null
  outputs.filter { it.hasContent }.forEach { output ->
    lastId = addAssistantMessage(TurnTexts.planStepOutputMessage(output), output.cardSpecs.firstOrNull())
    output.cardSpecs.drop(1).forEach { spec -> lastId = addAssistantMessage("", spec) }
  }
  return lastId
}

/** Name of the inline-card tool — shared by the extraction (here) and the draft filter in ReflectionOrchestrator. */
internal const val RENDER_CARD_TOOL = "render_card"

/**
 * What a finished turn means. The other pole of the mode contract: [resolveTurnExecutionPlan]
 * decides HOW to run a turn, this structure — WHAT its result means.
 */
data class CompletedTurn(
  /** Text of the assistant message to write to the DB (may be empty for a card-only answer). */
  val responseText: String,
  /** render_card specs from the trace — they become inline chat cards (the first one goes with the text). */
  val cardSpecs: List<String>,
  /**
   * The "empty answer" error for the banner (null otherwise). It is also the only "there is something
   * to retry" signal: TurnManager enables "Retry" exactly when blankError != null and the user's text
   * is still in the session — there is no separate canRetry field, so two flags cannot drift apart.
   */
  val blankError: String?,
)

/**
 * A pure decision on a turn's result (symmetric to [resolveTurnExecutionPlan], covered by
 * TurnCompletionTest): blank errors, render_card extraction. No I/O — TurnManager only applies the
 * decision.
 *
 * Applies the same way to CHAT/REFLECT/TUTOR. PLAN/GOAL turns of the Structured Plan Engine
 * ([StructuredPlanOrchestrator]) end with [AgentEvent.PlanProposed]/[AgentEvent.PlanProgressed]/
 * [AgentEvent.PlanBlocked]/[AgentEvent.Error] and never get here (see the kdoc of [AgentMode]).
 *
 * Task 15 (structured-plan-engine-907066) removed from here the old prompt-only NEED_INFO gate of plan
 * mode and the GOAL fallback for an empty answer at the iteration limit — both belonged to the retired
 * prompt-only PLAN/GOAL (and so did the `mode`/`planApproved` parameters they depended on), not to
 * CHAT/REFLECT/TUTOR, which this function still serves with no change in behavior.
 *
 * Before Task 5 (structured-plan-engine-907066) [CompletedTurn] also carried `planAwaitingApproval` —
 * a "non-empty plan shown, waiting for a decision" flag TurnManager used to raise a per-turn approval
 * bar in the chat. That flag is gone: whether a plan is shown and waiting for a decision is now the
 * state of the persisted AgentPlan in PlanRepository (see
 * com.aiopenpocket.agent.core.plan.execution.PlanCommands), not something derived from one turn.
 */
fun resolveCompletedTurn(result: AgentResult): CompletedTurn {
  val cardSpecs = result.toolTrace
    .filter { it.toolName == RENDER_CARD_TOOL }
    .mapNotNull { (it.result as? ToolResult.Success)?.data }

  // The iteration limit is a visible outcome, not silence: hitIterationLimit used not to be read here
  // at all. stoppedByToolBudget is a plan step's budget, CHAT/REFLECT/TUTOR have none; just in case,
  // it is not passed off as the chat's step limit.
  val responseText = if (result.hitIterationLimit && !result.stoppedByToolBudget) {
    listOf(result.text.trim(), TurnTexts.ITERATION_LIMIT_NOTE).filter { it.isNotEmpty() }.joinToString("\n\n")
  } else {
    result.text
  }

  val blankReply = responseText.isBlank() && cardSpecs.isEmpty()
  val blankError = if (blankReply) TurnTexts.BLANK_REPLY_ERROR else null

  return CompletedTurn(
    responseText = responseText,
    cardSpecs = cardSpecs,
    blankError = blankError,
  )
}
