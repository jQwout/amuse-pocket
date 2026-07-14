package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361
// @trace structured-plan-engine-907066

/**
 * Режим работы агента в чате (эфемерный UI-флаг, как plan-mode в Claude Code).
 *
 * - [CHAT] — обычный ReAct-цикл.
 * - [PLAN] — план по Structured Plan Engine: без инструментов, заканчивается persisted proposal'ом.
 * - [GOAL] — выполнение одобренного плана по шагам (Structured Plan Engine).
 * - [REFLECT] — черновик → критика → итог: отдельный оркестратор ([ReflectionOrchestrator]),
 *   а не гейт/петля поверх одного хода, поэтому [resolveTurnExecutionPlan] его не обрабатывает.
 * - [TUTOR] — разовый вопрос к LLM во время урока: без инструментов, ровно один ход.
 *
 * Task 10 (structured-plan-engine-907066): [com.aiopenpocket.di.TurnModule]'s `TurnExecutor`
 * dispatches [PLAN]/[GOAL] to `com.aiopenpocket.agent.loop.StructuredPlanOrchestrator`
 * (`propose`/`executeGoal`), exactly like it already dispatches [REFLECT] to
 * [ReflectionOrchestrator] — never to [AgentLoopOrchestrator]/[resolveTurnExecutionPlan] below, which
 * now only ever runs for [CHAT]/[TUTOR]. Task 15 (structured-plan-engine-907066) removed the OLD
 * prompt-only PLAN/GOAL gate/loop that used to live in [resolveTurnExecutionPlan]/[resolveCompletedTurn]
 * (and the `prompts/modes/*.md` assets it depended on) — Structured Plan Engine is now the only
 * PLAN/GOAL implementation.
 *
 * Контракт режима (для [CHAT]/[TUTOR]): [resolveTurnExecutionPlan] (как выполнять ход) +
 * [resolveCompletedTurn] (что значит его результат).
 */
enum class AgentMode { CHAT, PLAN, GOAL, REFLECT, TUTOR }

/**
 * Как выполнять текущий ход агента: давать ли инструменты и сколько витков. Чистое решение,
 * выведенное из режима — вынесено из оркестратора, чтобы покрыть контракт гейта тестом.
 */
data class TurnExecutionPlan(
  val usesTools: Boolean,
  val maxIterations: Int,
  /** Доверенная добавка к системному промпту (сейчас — только рамка tutor-хода). */
  val systemPromptSuffix: String? = null,
)

/**
 * Контракт CHAT/TUTOR в одном месте (PLAN/GOAL идут через Structured Plan Engine — см. kdoc [AgentMode]):
 * - CHAT — обычный цикл на [chatMaxIterations].
 * - TUTOR — разовый вопрос к LLM во время урока: БЕЗ инструментов, ровно один ход (только объясняет).
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
