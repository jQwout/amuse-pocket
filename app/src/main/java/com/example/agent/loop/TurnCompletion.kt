package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361

import com.aiopenpocket.agent.core.mcp.ToolResult

/**
 * Все пользовательские тексты слоя ходов — единственное место (задел под i18n: UI-слой сможет
 * подменить их ресурсами, не трогая логику). Логика на них только ССЫЛАЕТСЯ, не порождает свои.
 */
object TurnTexts {
  /** Гоал-мод упёрся в лимит витков с пустым текстом — честный частичный итог вместо пустоты. */
  const val GOAL_ITERATION_LIMIT_PARTIAL =
    "Достиг лимита витков — задача выполнена частично. Подробности — в трассировке инструментов."

  /** NEED_INFO-ответ, из которого после вырезания маркера ничего не осталось. */
  const val NEED_INFO_FALLBACK_QUESTION = "Уточни, пожалуйста, детали задачи — что именно нужно сделать?"

  const val BLANK_PLAN_ERROR = "Модель не вернула план — попробуй ещё раз."
  const val BLANK_REPLY_ERROR = "Модель вернула пустой ответ — попробуй ещё раз."

  /** Реплика, которой ход одобрения плана попадает в историю. */
  const val APPROVE_PLAN_MESSAGE = "Одобряю план, выполни его по шагам."

  /** Превью пуша, когда ход завершился без текста (card-only ответ). */
  const val TURN_DONE_FALLBACK = "Готово"
}

/** Маркер план-планирования: модель ставит его в начало ответа-вопроса (вырезается из текста). */
const val NEED_INFO_MARKER = "[[NEED_INFO]]"

private const val RENDER_CARD_TOOL = "render_card"

/**
 * Что означает завершившийся ход. Второй полюс контракта режимов: [resolveTurnExecutionPlan]
 * решает, КАК выполнять ход, эта структура — ЧТО значит его результат.
 */
data class CompletedTurn(
  /** Текст ассистентского сообщения для записи в БД (может быть пуст при card-only ответе). */
  val responseText: String,
  /** render_card-спеки из трейса — станут инлайн-карточками чата (первая — вместе с текстом). */
  val cardSpecs: List<String>,
  /** Ошибка «пустой ответ» для баннера (иначе null). */
  val blankError: String?,
  val canRetry: Boolean,
  /** План-мод: показан непустой план, ждём «Выполнить»/«Поправить». */
  val planAwaitingApproval: Boolean,
)

/**
 * Чистое решение по результату хода (симметрично [resolveTurnExecutionPlan], покрыто
 * TurnCompletionTest): NEED_INFO-гейт план-мода, blank-ошибки, GOAL-фолбэк пустого ответа,
 * извлечение render_card. Никакого I/O — TurnManager лишь применяет решение.
 */
fun resolveCompletedTurn(
  mode: AgentMode,
  planApproved: Boolean,
  result: AgentResult,
  /** Есть ли сохранённая реплика пользователя для «Повторить» (см. TurnSession.lastUserText). */
  hasRetryContext: Boolean,
): CompletedTurn {
  val cardSpecs = result.toolTrace
    .filter { it.toolName == RENDER_CARD_TOOL }
    .mapNotNull { (it.result as? ToolResult.Success)?.data }

  // Гоал-мод: на лимите витков не отдаём пустой ответ — честный частичный итог (трейс есть в UI).
  val rawText = if (mode == AgentMode.GOAL && result.text.isBlank()) {
    TurnTexts.GOAL_ITERATION_LIMIT_PARTIAL
  } else {
    result.text
  }

  // Фаза планирования: маркер [[NEED_INFO]] В НАЧАЛЕ ответа = уточняющий вопрос, не план.
  val planningPhase = mode == AgentMode.PLAN && !planApproved
  val needInfo = planningPhase && rawText.trimStart().startsWith(NEED_INFO_MARKER)
  val responseText = when {
    needInfo -> rawText.trimStart().removePrefix(NEED_INFO_MARKER).trim()
      .ifBlank { TurnTexts.NEED_INFO_FALLBACK_QUESTION }

    planningPhase -> rawText.trim()
    else -> rawText
  }

  val blankReply = responseText.isBlank() && cardSpecs.isEmpty()
  val blankError = when {
    planningPhase && blankReply -> TurnTexts.BLANK_PLAN_ERROR
    blankReply -> TurnTexts.BLANK_REPLY_ERROR
    else -> null
  }

  return CompletedTurn(
    responseText = responseText,
    cardSpecs = cardSpecs,
    blankError = blankError,
    canRetry = blankReply && hasRetryContext,
    planAwaitingApproval = planningPhase && !needInfo && responseText.isNotBlank(),
  )
}
