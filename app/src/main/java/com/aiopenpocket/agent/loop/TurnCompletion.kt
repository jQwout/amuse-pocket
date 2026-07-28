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
  const val BLANK_REPLY_ERROR = "Модель вернула пустой ответ — попробуй ещё раз."

  /** Превью пуша, когда ход завершился без текста (card-only ответ). */
  const val TURN_DONE_FALLBACK = "Готово"

  /** Ход не уложился в окно фоновой защиты (shortService, ~3 мин) при свёрнутом приложении. */
  const val PROTECTION_EXPIRED =
    "Ход не уложился в три минуты фоновой работы и был остановлен системой. Нажми «Повторить»."

  /**
   * Прогресс в пузыре стрима, пока агент работает инструментом между витками ReAct. Без него
   * преамбула прошлого витка гасла в пустоту на следующем витке (resetStreaming) и выглядела как
   * «ответ пишется, потом затирается». [TOOL_PROGRESS] — общий (до того как известно имя),
   * [toolProgress] — с именем инструмента.
   */
  const val TOOL_PROGRESS = "Использую инструмент…"

  fun toolProgress(toolName: String): String = "Использую инструмент: $toolName…"

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
 * TurnCompletionTest): blank-ошибки, извлечение render_card. Никакого I/O — TurnManager лишь
 * применяет решение.
 *
 * Применяется одинаково к CHAT/REFLECT/TUTOR. PLAN/GOAL турны Structured Plan Engine
 * ([StructuredPlanOrchestrator]) заканчиваются [AgentEvent.PlanProposed]/[AgentEvent.PlanProgressed]/
 * [AgentEvent.PlanBlocked]/[AgentEvent.Error] и сюда не попадают вовсе (см. kdoc [AgentMode]).
 *
 * Task 15 (structured-plan-engine-907066) убрал отсюда старый prompt-only NEED_INFO-гейт план-мода
 * и GOAL-фолбэк пустого ответа на лимите витков — обе механики принадлежали снятому prompt-only
 * PLAN/GOAL (значит, и параметры `mode`/`planApproved`, от которых они зависели), а не
 * CHAT/REFLECT/TUTOR, которые эта функция по-прежнему обслуживает без изменений в поведении.
 *
 * До Task 5 (structured-plan-engine-907066) [CompletedTurn] нёс ещё и `planAwaitingApproval` — флаг
 * «показан непустой план, ждём решения», которым TurnManager поднимал per-turn approval-бар в чате.
 * Тот флаг убран: показан ли план и ждёт ли он решения — теперь состояние persisted AgentPlan в
 * PlanRepository (см. com.aiopenpocket.agent.core.plan.execution.PlanCommands), а не производная
 * одного хода.
 */
fun resolveCompletedTurn(result: AgentResult): CompletedTurn {
  val cardSpecs = result.toolTrace
    .filter { it.toolName == RENDER_CARD_TOOL }
    .mapNotNull { (it.result as? ToolResult.Success)?.data }

  val blankReply = result.text.isBlank() && cardSpecs.isEmpty()
  val blankError = if (blankReply) TurnTexts.BLANK_REPLY_ERROR else null

  return CompletedTurn(
    responseText = result.text,
    cardSpecs = cardSpecs,
    blankError = blankError,
  )
}
