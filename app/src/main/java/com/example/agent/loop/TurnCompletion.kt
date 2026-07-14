package com.aiopenpocket.agent.loop

// @trace rezhimy-agenta-plan-i-goal-ee2361
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.plan.execution.PlanRunResult
import com.aiopenpocket.agent.core.plan.model.PlanRunOutcome

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

  /** Превью пуша, когда ход завершился без текста (card-only ответ). */
  const val TURN_DONE_FALLBACK = "Готово"

  /**
   * Task 10 (structured-plan-engine-907066): PLAN turn persisted a proposal —
   * [com.aiopenpocket.ui.chat.ChatViewModel]'s own reactive subscription to
   * `PlanRepository.observeByConversation` is what actually raises the approval bar
   * (`ChatUiState.pendingPlanApproval`); this text is only the chat-history record of the event.
   */
  fun planProposedMessage(planId: String, version: Int): String =
    "📋 План предложен (версия $version). Проверь его выше и одобри, чтобы начать выполнение."

  /** Task 10: GOAL turn's [PlanRunResult] rendered as a plain assistant message — one branch per [PlanRunOutcome]. */
  fun planProgressMessage(result: PlanRunResult): String = when (result.outcome) {
    PlanRunOutcome.COMPLETED -> "✅ План выполнен: все шаги завершены."
    PlanRunOutcome.PARTIAL -> if (result.executedStepIds.isEmpty()) {
      result.blockedReason ?: "Пока нет шагов, готовых к выполнению."
    } else {
      "Выполнено шагов: ${result.executedStepIds.joinToString()}. " +
        (result.blockedReason ?: "Продолжи, когда будешь готов.")
    }
    PlanRunOutcome.FAILED -> result.blockedReason ?: "Выполнение шага плана завершилось ошибкой."
    PlanRunOutcome.CANCELLED -> "Выполнение плана отменено."
  }
}

/** Маркер план-планирования: модель ставит его в начало ответа-вопроса (вырезается из текста). */
const val NEED_INFO_MARKER = "[[NEED_INFO]]"

/** Имя тула инлайн-карточек — общее для извлечения (здесь) и фильтра черновика в ReflectionOrchestrator. */
internal const val RENDER_CARD_TOOL = "render_card"

/**
 * Что означает завершившийся ход. Второй полюс контракта режимов: [resolveTurnExecutionPlan]
 * решает, КАК выполнять ход, эта структура — ЧТО значит его результат.
 */
data class CompletedTurn(
  /** Текст ассистентского сообщения для записи в БД (может быть пуст при card-only ответе). */
  val responseText: String,
  /** render_card-спеки из трейса — станут инлайн-карточками чата (первая — вместе с текстом). */
  val cardSpecs: List<String>,
  /**
   * Ошибка «пустой ответ» для баннера (иначе null). Заодно единственный признак «есть что
   * повторять»: TurnManager включает «Повторить» ровно когда blankError != null и в сессии остался
   * текст пользователя — отдельного canRetry-поля нет, чтобы два флага не разъезжались.
   */
  val blankError: String?,
)

/**
 * Чистое решение по результату хода (симметрично [resolveTurnExecutionPlan], покрыто
 * TurnCompletionTest): NEED_INFO-гейт план-мода, blank-ошибки, GOAL-фолбэк пустого ответа,
 * извлечение render_card. Никакого I/O — TurnManager лишь применяет решение.
 *
 * Про [AgentMode.REFLECT]: сюда он тоже доходит (TurnManager отдаёт mode рефлексивных ходов), но
 * не проходит через [resolveTurnExecutionPlan]. planningPhase=false и GOAL-фолбэк его не касаются —
 * REFLECT обрабатывается как обычный чат-ответ (покрыто TurnCompletionTest).
 *
 * До Task 5 (structured-plan-engine-907066) [CompletedTurn] нёс ещё и `planAwaitingApproval` — флаг
 * «показан непустой план, ждём решения», которым TurnManager поднимал per-turn approval-бар в чате.
 * Тот флаг убран: показан ли план и ждёт ли он решения — теперь состояние persisted AgentPlan в
 * PlanRepository (см. com.aiopenpocket.agent.core.plan.execution.PlanCommands), а не производная
 * одного хода. `planningPhase`/`needInfo` здесь остаются — они по-прежнему решают, как читать
 * responseText/blankError фазы планирования, просто больше не публикуются наружу отдельным полем.
 */
fun resolveCompletedTurn(
  mode: AgentMode,
  planApproved: Boolean,
  result: AgentResult,
): CompletedTurn {
  val cardSpecs = result.toolTrace
    .filter { it.toolName == RENDER_CARD_TOOL }
    .mapNotNull { (it.result as? ToolResult.Success)?.data }

  // Гоал-мод: если цикл УПЁРСЯ В ЛИМИТ витков с пустым текстом — честный частичный итог вместо
  // пустоты (трейс есть в UI). Чистый финиш без текста (напр. только render_card или просто пустой
  // ответ) под фолбэк не подводим — иначе «частичный итог» приписался бы успешному card-only ходу.
  val rawText = if (mode == AgentMode.GOAL && result.text.isBlank() && result.hitIterationLimit) {
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
  )
}
