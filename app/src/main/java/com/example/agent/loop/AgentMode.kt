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
 *
 * Task 10 (structured-plan-engine-907066): [com.aiopenpocket.di.TurnModule]'s `TurnExecutor`
 * dispatches [PLAN]/[GOAL] to `com.aiopenpocket.agent.loop.StructuredPlanOrchestrator`
 * (`propose`/`executeGoal`), exactly like it already dispatches [REFLECT] to
 * [ReflectionOrchestrator] — never to [AgentLoopOrchestrator]/[resolveTurnExecutionPlan] below.
 * [resolveTurnExecutionPlan]'s PLAN/GOAL branches (and [resolveCompletedTurn]'s matching ones) are
 * the OLD prompt-only implementation: kept compiling and covered by their existing tests (unchanged
 * behavior contract), but unreachable from the live chat path — Task 15 removes them once the legacy
 * PLAN/GOAL prompt assets they depend on are also retired.
 *
 * Контракт режима целиком (для [CHAT]/[TUTOR], и for legacy PLAN/GOAL still tested but dead):
 * [resolveTurnExecutionPlan] (как выполнять ход) + [resolveCompletedTurn] (что значит его результат).
 */
enum class AgentMode { CHAT, PLAN, GOAL, REFLECT, TUTOR }

/**
 * Какую секцию мода подмешивать в системный промпт. Выводится оркестратором из [AgentMode] и того,
 * одобрен ли уже план: одно и то же [AgentMode.PLAN] даёт разный промпт в фазах планирования и выполнения.
 */
enum class PromptMode { NONE, PLAN_PLANNING, PLAN_EXECUTING, GOAL }

/**
 * Как выполнять текущий ход агента: давать ли инструменты, сколько витков и какую секцию промпта.
 * Чистое решение, выведенное из режима — вынесено из оркестратора, чтобы покрыть контракт гейта/петли тестом.
 */
data class TurnExecutionPlan(
  val usesTools: Boolean,
  val maxIterations: Int,
  val promptMode: PromptMode,
  /** Доверенная добавка к системному промпту (сейчас — только бюджет витков гоал-мода). */
  val systemPromptSuffix: String? = null,
)

/**
 * Контракт режимов в одном месте:
 * - PLAN до одобрения — фаза планирования: БЕЗ инструментов, ровно один ход (гейт).
 * - PLAN после одобрения — выполнение: инструменты + полный лимит чата.
 * - GOAL — петля до [goalMaxIterations] (зажата в [1, chatMaxIterations]).
 * - CHAT — обычный цикл на [chatMaxIterations].
 * - TUTOR — разовый вопрос к LLM во время урока: БЕЗ инструментов, ровно один ход (только объясняет).
 */
fun resolveTurnExecutionPlan(
  mode: AgentMode,
  planApproved: Boolean,
  goalMaxIterations: Int,
  chatMaxIterations: Int,
): TurnExecutionPlan {
  val planning = mode == AgentMode.PLAN && !planApproved
  val tutor = mode == AgentMode.TUTOR
  val promptMode = when {
    planning -> PromptMode.PLAN_PLANNING
    mode == AgentMode.PLAN -> PromptMode.PLAN_EXECUTING
    mode == AgentMode.GOAL -> PromptMode.GOAL
    else -> PromptMode.NONE
  }
  val maxIterations = when {
    planning -> 1
    tutor -> 1
    mode == AgentMode.GOAL -> goalMaxIterations.coerceIn(1, chatMaxIterations)
    else -> chatMaxIterations
  }
  // В гоал-моде сообщаем модели бюджет витков — иначе goal.md обещает «честно остановиться на
  // лимите», но модель не знает, какой виток последний, и обычно уходит в обрыв на tool_call.
  val suffix = when {
    mode == AgentMode.GOAL ->
      "Бюджет: не более " + maxIterations + " витков (рассуждение, действие, " +
        "самопроверка). На последнем витке дай финальный ответ или честный частичный итог — не начинай новых действий."
    tutor ->
      "Ты отвечаешь на разовый вопрос ученика во время урока. Коротко объясни по теме урока и остановись — " +
        "не продолжай урок сам и не задавай встречных заданий."
    else -> null
  }
  return TurnExecutionPlan(
    usesTools = !planning && !tutor,
    maxIterations = maxIterations,
    promptMode = promptMode,
    systemPromptSuffix = suffix,
  )
}
