package com.aiopenpocket.agent.loop

// @trace rezhim-refleksii-fca3c4
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.prompt.REFLECTION_FINAL_HEADING
import com.aiopenpocket.agent.core.AgentLog
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Reflection orchestrator — a teaching demo built ON TOP of the single [ReactLoopRunner], not a
 * separate engine. It runs three phases over one conversation:
 *
 *  1. **Draft** — the shared ReAct loop produces a first-pass answer (agent-scoped tool set, same as [AgentLoopOrchestrator]).
 *  2. **Critique** — one tool-less LLM call reads the draft and names concrete weaknesses.
 *  3. **Revise** — the shared ReAct loop rewrites the answer, addressing the critique. It only gets
 *     tools that can do nothing but read (plus `render_card`) and is told which actions the draft
 *     already performed — see [reviseTools] for why.
 *
 * Everything streams as plain [AgentEvent.TextDelta] so the existing chat UI shows the whole
 * draft → critique → final process live, and the accumulated transcript is returned as
 * [AgentResult.text] so it persists as one assistant message. No new card, no new engine.
 *
 * Selected by [AgentMode.REFLECT]: the [com.aiopenpocket.di.TurnModule] TurnExecutor routes REFLECT
 * turns here instead of through [AgentLoopOrchestrator], because a 3-phase loop doesn't fit the
 * single-turn resolveTurnExecutionPlan contract.
 */
@SingleIn(AppScope::class)
@Inject
class ReflectionOrchestrator(
  private val llmClient: LlmClient,
  private val registry: ToolRegistry,
  private val prePromptAssembler: PrePromptAssembler,
  private val reactLoopRunner: ReactLoopRunner,
  private val conversationAnnotator: ConversationAnnotator,
  private val turnContextHolder: TurnContextHolder,
  private val log: AgentLog,
) {

  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider,
    executionMode: ExecutionMode = ExecutionMode.LIVE,
  ): Flow<AgentEvent> = agentTurnFlow(log, TAG, REFLECTION_FAILED) {
    // Каждый TextDelta зеркалим в transcript, чтобы сохранённое сообщение совпало с тем, что
    // пользователь видел в стриме (черновик + критика + итог), а не только с финальным ответом.
    val transcript = StringBuilder()
    val tap: suspend (AgentEvent) -> Unit = { ev ->
      if (ev is AgentEvent.TextDelta) transcript.append(ev.content)
      send(ev)
    }
    // Общий выход по ошибке фазы: ERROR-состояние от ReactLoopRunner превращаем в терминальное
    // событие ошибки, а не в мнимый «успех». Досрочный выход из хода — return@agentTurnFlow.
    suspend fun emitError(message: String?) {
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(message ?: REFLECTION_FAILED))
    }

    val context = prePromptAssembler.assemble(conversationId, userMessage)
    log.d(TAG, "▶ reflection conv=$conversationId msg=\"${userMessage.take(60)}\"")

    // Скоуп мини-агента/custom agent — те же scoped schemas + agent snapshot, что AgentLoopOrchestrator
    // передаёт обычному чат-ходу. Раньше обе фазы (draft/revise) получали registry.getAllSchemas() без
    // agent — REFLECT-ход custom агента обходил бы его toolNames/skillIds allowlist целиком.
    val tools = context.toolNames?.let { registry.getSchemasFor(it) } ?: registry.getAllSchemas()

    // 1. DRAFT — обычный ReAct-цикл со scoped-набором тулов. context.history уже заканчивается
    // текущим сообщением пользователя (его персистит вызывающий до execute()).
    tap(AgentEvent.StateChanged(AgentState.STREAMING))
    tap(AgentEvent.TextDelta("✍️ **Черновик**\n"))
    val draftMessages = mutableListOf(ChatMessage(role = "system", content = context.systemPrompt))
    draftMessages += context.history.map { it.toChatMessage() }
    // Тот же snapshot агента, что видят scoped tools/schemas, должны видеть и memory/file-тулы
    // внутри этого хода (Task 5) — без этой обёртки TurnContextHolder остался бы пустым на
    // протяжении всего REFLECT-хода, и remember_fact/search_memory работали бы по ГЛОБАЛЬНОМУ
    // пространству имён даже для агента со своим namespace (тот же класс дыры, что и REFLECT
    // tool-scope до фикса в Task 4, только для памяти, а не allowlist тулов).
    val draft = withContext(turnContextHolder.contextElement(conversationId, context.agent)) {
      reactLoopRunner.run(
        conversationId, draftMessages, tools, provider,
        agent = context.agent, executionMode = executionMode, emit = { tap(it) },
      )
    }
    log.d(TAG, "draft len=${draft.text.length}")
    // ReactLoopRunner на сбое стрима не бросает, а возвращает state=ERROR. Не проглатываем: как и
    // обычный чат-цикл, поднимаем ошибку наружу и не тратим critique/revise на пустой черновик.
    if (draft.state == AgentState.ERROR) {
      return@agentTurnFlow emitError(draft.errorMessage)
    }

    // 2. CRITIQUE — один tool-less вызов: назвать конкретные слабые места черновика.
    tap(AgentEvent.TextDelta("\n\n🔍 **Критика**\n"))
    val critique = runCritic(userMessage, draft.text, provider) { tap(it) }

    // 3. REVISE — снова общий цикл, но только с тулами чтения и карточкой (см. reviseTools): черновик
    // свои действия уже выполнил по-настоящему, правка переписывает ТЕКСТ, а не повторяет дела.
    tap(AgentEvent.TextDelta(FINAL_HEADING))
    val reviseMessages = mutableListOf(ChatMessage(role = "system", content = context.systemPrompt))
    reviseMessages += context.history.map { it.toChatMessage() }
    reviseMessages += ChatMessage(
      role = "user",
      content = buildReviseInstruction(draft.text, critique, draft.toolTrace, executionMode),
    )
    val revised = withContext(turnContextHolder.contextElement(conversationId, context.agent)) {
      reactLoopRunner.run(
        conversationId, reviseMessages, reviseTools(tools), provider,
        agent = context.agent, executionMode = executionMode, emit = { tap(it) },
      )
    }
    log.d(TAG, "✓ reflection done revisedLen=${revised.text.length}")
    if (revised.state == AgentState.ERROR) {
      return@agentTurnFlow emitError(revised.errorMessage)
    }
    // Пустой финал (revise не дал ни текста, ни карточки): декоративные заголовки ✍/🔍/✨ делают
    // transcript непустым, поэтому blank-гейт resolveCompletedTurn для REFLECT не сработал бы — юзер
    // получил бы три заголовка без содержимого. Гасим тем же blank-error + «Повторить», что и обычный ход.
    val revisedHasCard = revised.toolTrace.any { it.toolName == RENDER_CARD_TOOL }
    if (revised.text.isBlank() && !revisedHasCard) {
      return@agentTurnFlow emitError(TurnTexts.BLANK_REPLY_ERROR)
    }

    send(AgentEvent.StateChanged(AgentState.FINISHED))
    send(
      AgentEvent.Completed(
        AgentResult(
          text = transcript.toString(),
          // Карточки (render_card) берём только из фазы правки: черновик — выброшенный, его
          // карточки не должны отрисоваться как финальные. Остальной трейс черновика оставляем для
          // прозрачности (видно, чем агент пользовался на первом проходе). Критика тулы не вызывает.
          toolTrace = draft.toolTrace.filterNot { it.toolName == RENDER_CARD_TOOL } + revised.toolTrace,
          state = AgentState.FINISHED,
          // Число фаз (draft → critique → revise), не LLM-раундов — поле только для отладочного лога.
          iterations = 3,
        ),
      ),
    )
    // Как и обычный чат-ход: заголовок/резюме/теги — отдельно от хода (см. annotateInBackground), иначе
    // лишний вызов модели держал бы ход и foreground-сервис, а runCatching глотал бы отмену.
    conversationAnnotator.annotateInBackground(conversationId) {
      log.w(TAG, "annotate failed for $conversationId: ${it.message}")
    }
  }

  /** Critique phase: a single tool-less call that returns concrete weaknesses; streamed and returned. */
  private suspend fun runCritic(
    userMessage: String,
    draft: String,
    provider: LlmProvider,
    emit: suspend (AgentEvent) -> Unit,
  ): String {
    val messages = listOf(
      ChatMessage(role = "system", content = CRITIC_SYSTEM),
      ChatMessage(
        role = "user",
        content = "Запрос пользователя:\n$userMessage\n\nЧерновик ответа:\n$draft\n\n" +
          "Назови конкретные слабые места черновика.",
      ),
    )
    val critique = StringBuilder()
    var streamError: String? = null
    // Критик без инструментов: это чистое рассуждение над текстом, тулы ему не нужны.
    llmClient.streamChat(messages, emptyList(), provider).collect { chunk ->
      when (chunk) {
        is StreamEvent.TextDelta -> {
          critique.append(chunk.content)
          emit(AgentEvent.TextDelta(chunk.content))
        }
        // Не глотаем сбой стрима на фазе критики: бросаем, чтобы внешний catch поднял ту же
        // терминальную ошибку, что и для draft/revise.
        is StreamEvent.Error -> streamError = chunk.message
        else -> {}
      }
    }
    streamError?.let { throw IllegalStateException(it) }
    return critique.toString()
  }

  /**
   * The revise phase's tool set: [scoped] narrowed to `render_card` plus tools that can do nothing
   * but read. Раньше правка получала тот же набор, что и черновик, а инструкция велела «вызвать
   * нужный инструмент заново» — и черновик, уже создавший событие или поставивший напоминание по-
   * настоящему (LIVE), повторял это второй раз. Правка переписывает текст; всё, что меняет мир,
   * черновик уже сделал.
   *
   * «Только читает» проверяется по КАЖДОМУ действию из `action`-enum схемы через
   * [com.aiopenpocket.agent.core.mcp.Tool.riskFor], а не по потолку `riskClass`: у части тулов
   * (memory_tool, heartbeat) потолок занижен до READ при пишущих действиях, и он один пропустил бы
   * `remember_fact`/`cancel`. Тул со смешанными действиями (calendar: list + delete) в правку не
   * попадает целиком — схема не умеет показать модели половину enum'а. Тул с параметром `action`
   * без enum'а не классифицировать честно — его тоже не даём. Рантайм-гейт ReactLoopRunner
   * (allowedToolNames) отклонит любой вызов вне этого списка, даже если модель его «вспомнит».
   */
  private fun reviseTools(scoped: List<FunctionSchema>): List<FunctionSchema> = scoped.filter { schema ->
    val name = schema.function.name
    val tool = registry.get(name) ?: return@filter false
    if (name == RENDER_CARD_TOOL) return@filter true
    val actionParam = (schema.function.parameters["properties"] as? JsonObject)?.get("action") as? JsonObject
    if (actionParam == null) {
      tool.riskClass == RiskClass.READ && tool.riskFor(emptyMap()) == RiskClass.READ
    } else {
      val actions = (actionParam["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
      actions.isNotEmpty() && actions.all { tool.riskFor(mapOf("action" to JsonPrimitive(it))) == RiskClass.READ }
    }
  }

  private fun buildReviseInstruction(
    draft: String,
    critique: String,
    draftTrace: List<ToolTrace>,
    executionMode: ExecutionMode,
  ): String = buildString {
    appendLine("Твой черновик ответа:")
    appendLine(draft)
    appendLine()
    appendLine("Критика черновика:")
    appendLine(critique)
    appendLine()
    // Карточки черновика — не «действия»: они выбрасываются и строятся заново (см. ниже).
    val draftActions = draftTrace.filterNot { it.toolName == RENDER_CARD_TOOL }
    if (draftActions.isNotEmpty()) {
      appendLine(
        if (executionMode == ExecutionMode.PREVIEW) {
          "Вызовы инструментов в черновике (это предпросмотр: всё, кроме чтения, только имитировалось):"
        } else {
          "Черновик УЖЕ выполнил эти вызовы инструментов — они сделаны, повторять их нельзя:"
        },
      )
      draftActions.forEach { appendLine(describeDraftCall(it)) }
      appendLine(
        "Опиши их итог в ответе честно, как в списке: что получилось, что нет. Сейчас тебе доступны " +
          "только чтение и карточки — повторить или исправить действие отсюда не выйдет; если что-то " +
          "не получилось, так и скажи и предложи пользователю попросить ещё раз.",
      )
      appendLine()
    }
    appendLine(
      "Перепиши ответ, устранив замечания критики. Дай пользователю только финальный ответ на " +
        "его языке — без пересказа критики и без слова «черновик». Если для ответа нужна " +
        "визуализация (карточка, таблица, график) — построй её заново: карточки из черновика в " +
        "финальный ответ не переносятся.",
    )
  }

  /** «- calendar.create_event — успешно» / «- email.compose — ошибка: …» — одна строка на вызов черновика. */
  private fun describeDraftCall(trace: ToolTrace): String {
    val action = trace.arguments.string("action")
    val label = if (action != null) "${trace.toolName}.$action" else trace.toolName
    val outcome = when (val result = trace.result) {
      is ToolResult.Success -> "успешно"
      is ToolResult.Error -> "ошибка: ${result.message.replace(Regex("\\s+"), " ").take(MAX_ERROR_CHARS)}"
    }
    return "- $label — $outcome"
  }

  companion object {
    private const val TAG = "Reflection"
    private const val REFLECTION_FAILED = "Рефлексия не получилась — попробуй ещё раз."

    /** Хватает на суть ошибки инструмента; длинный стек в промпте правки только мешал бы. */
    private const val MAX_ERROR_CHARS = 160

    /**
     * Заголовок финальной части в сохранённом транскрипте REFLECT. Сообщение хранится целиком
     * (черновик + критика + итог) — именно это пользователь смотрит в чате после хода. Тому, кто
     * собирает историю для СЛЕДУЮЩИХ ходов, достаточно `text.substringAfterLast(FINAL_HEADING)`:
     * черновик и критика модели в контексте не нужны и только раздувают его.
     */
    const val FINAL_HEADING = REFLECTION_FINAL_HEADING

    private const val CRITIC_SYSTEM =
      "Ты — строгий критик-редактор. Тебе дают запрос пользователя и черновик ответа на него. " +
        "Коротко, 3–5 пунктов, укажи конкретные слабые места черновика: фактические ошибки, " +
        "упущенные части запроса, неясные места, лишнее. Сам ответ НЕ переписывай — только " +
        "замечания. Если черновик уже хорош, честно так и скажи. Пиши на языке пользователя."
  }
}
