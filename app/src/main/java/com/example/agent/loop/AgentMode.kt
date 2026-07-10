package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361

/**
 * Режим работы агента в чате (эфемерный UI-флаг, как plan-mode в Claude Code).
 *
 * - [CHAT] — обычный ReAct-цикл.
 * - [PLAN] — гейт: сначала план без инструментов, ждём одобрения, потом выполнение с инструментами.
 * - [GOAL] — петля к цели с самопроверкой и лимитом витков.
 * - [REFLECT] — черновик → критика → итог: отдельный оркестратор ([ReflectionOrchestrator]),
 *   а не гейт/петля поверх одного хода, поэтому [resolveTurnExecutionPlan] его не обрабатывает.
 */
enum class AgentMode { CHAT, PLAN, GOAL, REFLECT }

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
 */
fun resolveTurnExecutionPlan(
  mode: AgentMode,
  planApproved: Boolean,
  goalMaxIterations: Int,
  chatMaxIterations: Int,
): TurnExecutionPlan {
  val planning = mode == AgentMode.PLAN && !planApproved
  val promptMode = when {
    planning -> PromptMode.PLAN_PLANNING
    mode == AgentMode.PLAN -> PromptMode.PLAN_EXECUTING
    mode == AgentMode.GOAL -> PromptMode.GOAL
    else -> PromptMode.NONE
  }
  val maxIterations = when {
    planning -> 1
    mode == AgentMode.GOAL -> goalMaxIterations.coerceIn(1, chatMaxIterations)
    else -> chatMaxIterations
  }
  // В гоал-моде сообщаем модели бюджет витков — иначе goal.md обещает «честно остановиться на
  // лимите», но модель не знает, какой виток последний, и обычно уходит в обрыв на tool_call.
  val suffix = if (mode == AgentMode.GOAL) {
    "Бюджет: не более " + maxIterations + " витков (рассуждение, действие, " +
      "самопроверка). На последнем витке дай финальный ответ или честный частичный итог — не начинай новых действий."
  } else {
    null
  }
  return TurnExecutionPlan(
    usesTools = !planning,
    maxIterations = maxIterations,
    promptMode = promptMode,
    systemPromptSuffix = suffix,
  )
}
