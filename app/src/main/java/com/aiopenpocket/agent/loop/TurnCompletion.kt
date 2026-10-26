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
 * Все пользовательские тексты слоя ходов — единственное место. Логика на них только ССЫЛАЕТСЯ, не
 * порождает свои. Тексты — геттеры с [tr], а не const: язык интерфейса меняется на лету.
 */
object TurnTexts {
  val BLANK_REPLY_ERROR: String
    get() = tr("Модель вернула пустой ответ — попробуй ещё раз.", "The model returned an empty reply — try again.")

  /** Превью пуша, когда ход завершился без текста (card-only ответ). */
  val TURN_DONE_FALLBACK: String get() = tr("Готово", "Done")

  /** Ход не уложился в окно фоновой защиты (shortService, ~3 мин) при свёрнутом приложении. */
  val PROTECTION_EXPIRED: String
    get() = tr(
      "Ход не уложился в три минуты фоновой работы и был остановлен системой. Нажми «Повторить».",
      "The turn didn't finish within three minutes of background work and the system stopped it. Tap “Retry”.",
    )

  /**
   * Прогресс в пузыре стрима, пока агент работает инструментом между витками ReAct. Без него
   * преамбула прошлого витка гасла в пустоту на следующем витке (resetStreaming) и выглядела как
   * «ответ пишется, потом затирается». [TOOL_PROGRESS] — общий (до того как известно имя),
   * [toolProgress] — с именем инструмента.
   */
  val TOOL_PROGRESS: String get() = tr("Использую инструмент…", "Using a tool…")

  fun toolProgress(toolName: String, arguments: Map<String, JsonElement> = emptyMap()): String =
    tr("Использую инструмент: ", "Using a tool: ") + "${CapabilityNames.call(toolName, arguments)}…"

  /** Ход упал не по вине модели (запись в базу, неожиданное исключение): подробности — в лог. */
  val TURN_FAILED: String get() = tr("Что-то пошло не так — попробуй ещё раз.", "Something went wrong — try again.")

  /**
   * Хвост ответа, когда цикл упёрся в лимит шагов, а модель так и не дала итог. Без него в чат
   * уходили преамбулы витков («сейчас посмотрю…») как будто это ответ, а при пустых преамбулах —
   * «пустой ответ». Подсказка «попроси продолжить» — потому что продолжение и правда работает:
   * результаты инструментов уже в эпизодической памяти диалога.
   */
  val ITERATION_LIMIT_NOTE: String
    get() = tr("_Остановился на лимите шагов — попроси продолжить._", "_Stopped at the step limit — ask me to continue._")

  /**
   * Task 10 (structured-plan-engine-907066): PLAN turn persisted a proposal —
   * [com.aiopenpocket.ui.chat.ChatViewModel]'s own reactive subscription to
   * `PlanRepository.observeByConversation` is what actually raises the approval bar
   * (`ChatUiState.pendingPlanApproval`); this text is only the chat-history record of the event.
   */
  // Карточка плана стоит НАД полем ввода, то есть под этим сообщением — отсюда «ниже».
  fun planProposedMessage(planId: String, version: Int): String =
    tr(
      "📋 План предложен (версия $version). Проверь его в карточке ниже и одобри, чтобы начать выполнение.",
      "📋 Plan proposed (version $version). Review it in the card below and approve it to start.",
    )

  /**
   * Task 10: GOAL turn's [PlanRunResult] rendered as a plain assistant message — one branch per [PlanRunOutcome].
   * Шаги называются по названиям ([PlanStepOutput.title]), не по id: раньше сюда попадало
   * «Выполнено шагов: collect_limits». Сам ответ шага — отдельным сообщением ([planStepOutputMessage]).
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

  /** «Шаг «X» выполнен.» по шагам, которые в этом запуске дошли до COMPLETED; null — таких нет. */
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
   * Ответ исполненного шага плана как сообщение ассистента: заголовок с названием шага (в фоне такие
   * сообщения приходят без реплики пользователя — без заголовка непонятно, к чему они) и сам текст.
   * Ответ из одних карточек остаётся без текста — как card-only ответ обычного хода.
   */
  fun planStepOutputMessage(output: PlanStepOutput): String {
    val text = output.text.trim()
    return if (text.isEmpty()) "" else tr("**Шаг «${output.title}»**", "**Step “${output.title}”**") + "\n\n$text"
  }
}

/**
 * Пишет ответы исполненных шагов плана ([PlanRunResult.stepOutputs]) в чат: текст с первой карточкой,
 * остальные карточки — отдельными сообщениями, как [TurnManager] делает для обычного хода. Общая
 * для foreground-хода (TurnManager, через TurnPersistence) и фонового heartbeat'а (HeartbeatRunner,
 * через ConversationRepository) — у них разные порты записи, поэтому запись приходит лямбдой.
 * Шаги без текста и карточек пропускаются. Возвращает id последнего вставленного сообщения.
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

  // Лимит витков — видимый исход, а не тишина: раньше hitIterationLimit здесь не читался вовсе.
  // stoppedByToolBudget — бюджет шага плана, в CHAT/REFLECT/TUTOR его нет; на всякий случай не
  // выдаём его за лимит шагов чата.
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
