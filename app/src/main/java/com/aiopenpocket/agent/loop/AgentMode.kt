package com.aiopenpocket.agent.loop

// @trace streaming-llm-chat-6595a1
// @trace structured-plan-engine-907066

/**
 * The agent's mode in the chat (an ephemeral UI flag, like plan mode in Claude Code).
 *
 * - [CHAT] — the normal ReAct loop.
 * - [PLAN] — a plan via the Structured Plan Engine: no tools, ends with a persisted proposal.
 * - [GOAL] — running an approved plan step by step (Structured Plan Engine).
 * - [REFLECT] — draft → critique → result: a separate orchestrator ([ReflectionOrchestrator]), not a
 *   gate/loop on top of one turn, so [resolveTurnExecutionPlan] does not handle it.
 * - [TUTOR] — a one-off question to the LLM during a lesson: no tools, exactly one turn.
 *
 * Task 10 (structured-plan-engine-907066): [com.aiopenpocket.di.TurnModule]'s `TurnExecutor`
 * dispatches [PLAN]/[GOAL] to `com.aiopenpocket.agent.loop.StructuredPlanOrchestrator`
 * (`propose`/`executeGoal`), exactly like it already dispatches [REFLECT] to
 * [ReflectionOrchestrator] — never to [AgentLoopOrchestrator]/[resolveTurnExecutionPlan] below, which
 * now only ever runs for [CHAT]/[TUTOR]. Task 15 (structured-plan-engine-907066) removed the OLD
 * prompt-only PLAN/GOAL gate/loop that used to live in [resolveTurnExecutionPlan]/[resolveCompletedTurn]
 * (and the `prompts/modes` assets it depended on) — Structured Plan Engine is now the only
 * PLAN/GOAL implementation.
 *
 * The mode contract (for [CHAT]/[TUTOR]): [resolveTurnExecutionPlan] (how to run a turn) +
 * [resolveCompletedTurn] (what its result means).
 */
enum class AgentMode { CHAT, PLAN, GOAL, REFLECT, TUTOR }

/**
 * How to run the current agent turn: whether to give tools and how many iterations. A pure decision
 * derived from the mode — moved out of the orchestrator so the gate contract can be covered by a test.
 */
data class TurnExecutionPlan(
  val usesTools: Boolean,
  val maxIterations: Int,
  /** A trusted addition to the system prompt (currently only the tutor-turn frame). */
  val systemPromptSuffix: String? = null,
)

/**
 * The CHAT/TUTOR contract in one place (PLAN/GOAL go through the Structured Plan Engine — see the
 * kdoc of [AgentMode]):
 * - CHAT — the normal loop with [chatMaxIterations].
 * - TUTOR — a one-off question to the LLM during a lesson: NO tools, exactly one turn (it only explains).
 */
fun resolveTurnExecutionPlan(
  mode: AgentMode,
  chatMaxIterations: Int,
): TurnExecutionPlan {
  val tutor = mode == AgentMode.TUTOR
  val suffix = if (tutor) {
    "Ты отвечаешь на разовый вопрос ученика во время урока. Коротко объясни по теме урока и остановись — " +
      "не продолжай урок сам и не задавай встречных заданий."
  } else {
    null
  }
  return TurnExecutionPlan(
    usesTools = !tutor,
    maxIterations = if (tutor) 1 else chatMaxIterations,
    systemPromptSuffix = suffix,
  )
}
