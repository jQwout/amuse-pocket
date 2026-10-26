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
    // Every TextDelta is mirrored into the transcript so the saved message matches what the user saw
    // in the stream (draft + critique + result), not only the final answer.
    val transcript = StringBuilder()
    val tap: suspend (AgentEvent) -> Unit = { ev ->
      if (ev is AgentEvent.TextDelta) transcript.append(ev.content)
      send(ev)
    }
    // Common exit on a phase error: the ERROR state from ReactLoopRunner becomes a terminal error event,
    // not a fake "success". An early exit from the turn is return@agentTurnFlow.
    suspend fun emitError(message: String?) {
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(message ?: REFLECTION_FAILED))
    }

    val context = prePromptAssembler.assemble(conversationId, userMessage)
    log.d(TAG, "▶ reflection conv=$conversationId msg=\"${userMessage.take(60)}\"")

    // The mini-agent/custom agent scope — the same scoped schemas + agent snapshot that
    // AgentLoopOrchestrator passes to a normal chat turn. Both phases (draft/revise) used to get
    // registry.getAllSchemas() without the agent — a custom agent's REFLECT turn would bypass its
    // toolNames/skillIds allowlist entirely.
    val tools = context.toolNames?.let { registry.getSchemasFor(it) } ?: registry.getAllSchemas()

    // 1. DRAFT — a normal ReAct loop with the scoped tool set. context.history already ends with the
    // current user message (the caller persists it before execute()).
    tap(AgentEvent.StateChanged(AgentState.STREAMING))
    tap(AgentEvent.TextDelta("✍️ **Черновик**\n"))
    val draftMessages = mutableListOf(ChatMessage(role = "system", content = context.systemPrompt))
    draftMessages += context.history.map { it.toChatMessage() }
    // The memory/file tools inside this turn must see the same agent snapshot that the scoped
    // tools/schemas see (Task 5) — without this wrapper TurnContextHolder would stay empty for the
    // whole REFLECT turn, and remember_fact/search_memory would work in the GLOBAL namespace even for
    // an agent with its own namespace (the same class of hole as the REFLECT tool scope before the fix
    // in Task 4, only for memory instead of the tool allowlist).
    val draft = withContext(turnContextHolder.contextElement(conversationId, context.agent)) {
      reactLoopRunner.run(
        conversationId, draftMessages, tools, provider,
        agent = context.agent, executionMode = executionMode, emit = { tap(it) },
      )
    }
    log.d(TAG, "draft len=${draft.text.length}")
    // ReactLoopRunner does not throw on a stream failure; it returns state=ERROR. We do not swallow it:
    // like the normal chat loop, we raise the error and do not spend critique/revise on an empty draft.
    if (draft.state == AgentState.ERROR) {
      return@agentTurnFlow emitError(draft.errorMessage)
    }

    // 2. CRITIQUE — a single tool-less call: name the concrete weak spots of the draft.
    tap(AgentEvent.TextDelta("\n\n🔍 **Критика**\n"))
    val critique = runCritic(userMessage, draft.text, provider) { tap(it) }

    // 3. REVISE — the shared loop again, but only with reading tools and the card (see reviseTools): the
    // draft has already performed its actions for real; the revision rewrites the TEXT, it does not
    // repeat the deeds.
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
    // An empty final (revise gave neither text nor a card): the decorative headings ✍/🔍/✨ make the
    // transcript non-empty, so resolveCompletedTurn's blank gate would not fire for REFLECT — the user
    // would get three headings with no content. Handle it with the same blank error + "Retry" as a
    // normal turn.
    val revisedHasCard = revised.toolTrace.any { it.toolName == RENDER_CARD_TOOL }
    if (revised.text.isBlank() && !revisedHasCard) {
      return@agentTurnFlow emitError(TurnTexts.BLANK_REPLY_ERROR)
    }

    send(AgentEvent.StateChanged(AgentState.FINISHED))
    send(
      AgentEvent.Completed(
        AgentResult(
          text = transcript.toString(),
          // Cards (render_card) come only from the revise phase: the draft is discarded, its cards must
          // not render as final ones. The rest of the draft's trace is kept for transparency (it shows
          // what the agent used on the first pass). The critique calls no tools.
          toolTrace = draft.toolTrace.filterNot { it.toolName == RENDER_CARD_TOOL } + revised.toolTrace,
          state = AgentState.FINISHED,
          // The number of phases (draft → critique → revise), not LLM rounds — the field is only for the debug log.
          iterations = 3,
        ),
      ),
    )
    // As with a normal chat turn: title/summary/tags are produced outside the turn (see
    // annotateInBackground), otherwise an extra model call would keep the turn and the foreground
    // service alive, and runCatching would swallow cancellation.
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
    // The critic has no tools: it is pure reasoning over text and needs none.
    llmClient.streamChat(messages, emptyList(), provider).collect { chunk ->
      when (chunk) {
        is StreamEvent.TextDelta -> {
          critique.append(chunk.content)
          emit(AgentEvent.TextDelta(chunk.content))
        }
        // Do not swallow a stream failure in the critique phase: throw, so the outer catch raises the same
        // terminal error as for draft/revise.
        is StreamEvent.Error -> streamError = chunk.message
        else -> {}
      }
    }
    streamError?.let { throw IllegalStateException(it) }
    return critique.toString()
  }

  /**
   * The revise phase's tool set: [scoped] narrowed to `render_card` plus tools that can do nothing
   * but read. The revision used to get the same set as the draft, while the instruction said "call
   * the needed tool again" — and a draft that had already created an event or set a reminder for
   * real (LIVE) did it a second time. The revision rewrites text; everything that changes the world
   * the draft has already done.
   *
   * "Read-only" is checked for EVERY action of the schema's `action` enum through
   * [com.aiopenpocket.agent.core.mcp.Tool.riskFor], not by the `riskClass` ceiling: some tools
   * (memory_tool, heartbeat) have the ceiling lowered to READ while having writing actions, and the
   * ceiling alone would let `remember_fact`/`cancel` through. A tool with mixed actions (calendar:
   * list + delete) is left out of the revision entirely — a schema cannot show the model half an
   * enum. A tool with an `action` parameter without an enum cannot be classified honestly — it is not
   * given either. ReactLoopRunner's runtime gate (allowedToolNames) rejects any call outside this list,
   * even if the model "remembers" it.
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
    // The draft's cards are not "actions": they are thrown away and built again (see below).
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

  /** "- calendar.create_event — success" / "- email.compose — error: …" — one line per draft call. */
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

    /** Enough for the gist of a tool error; a long stack trace in the revise prompt would only get in the way. */
    private const val MAX_ERROR_CHARS = 160

    /**
     * Heading of the final part in a saved REFLECT transcript. The message is stored in full (draft +
     * critique + result) — that is what the user sees in the chat after the turn. Whoever assembles
     * the history for NEXT turns only needs `text.substringAfterLast(FINAL_HEADING)`: the draft and
     * the critique are not needed in the model's context and would only bloat it.
     */
    const val FINAL_HEADING = REFLECTION_FINAL_HEADING

    private const val CRITIC_SYSTEM =
      "Ты — строгий критик-редактор. Тебе дают запрос пользователя и черновик ответа на него. " +
        "Коротко, 3–5 пунктов, укажи конкретные слабые места черновика: фактические ошибки, " +
        "упущенные части запроса, неясные места, лишнее. Сам ответ НЕ переписывай — только " +
        "замечания. Если черновик уже хорош, честно так и скажи. Пиши на языке пользователя."
  }
}
