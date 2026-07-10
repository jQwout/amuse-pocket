package com.aiopenpocket.agent.loop

// @trace rezhim-refleksii-fca3c4

import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Reflection orchestrator — a teaching demo built ON TOP of the single [ReactLoopRunner], not a
 * separate engine. It runs three phases over one conversation:
 *
 *  1. **Draft** — the shared ReAct loop produces a first-pass answer (full tool set).
 *  2. **Critique** — one tool-less LLM call reads the draft and names concrete weaknesses.
 *  3. **Revise** — the shared ReAct loop rewrites the answer, addressing the critique.
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
class ReflectionOrchestrator @Inject constructor(
  private val llmClient: LlmClient,
  private val registry: ToolRegistry,
  private val prePromptAssembler: PrePromptAssembler,
  private val reactLoopRunner: ReactLoopRunner,
  private val conversationAnnotator: ConversationAnnotator,
) {

  fun execute(
    conversationId: String,
    userMessage: String,
    provider: LlmProvider = LlmProvider.NIM,
  ): Flow<AgentEvent> = channelFlow {
    // Каждый TextDelta зеркалим в transcript, чтобы сохранённое сообщение совпало с тем, что
    // пользователь видел в стриме (черновик + критика + итог), а не только с финальным ответом.
    val transcript = StringBuilder()
    val tap: suspend (AgentEvent) -> Unit = { ev ->
      if (ev is AgentEvent.TextDelta) transcript.append(ev.content)
      send(ev)
    }
    // Общий выход по ошибке фазы — тот же контракт, что у AgentLoopOrchestrator: ERROR-состояние
    // от ReactLoopRunner превращаем в терминальное событие ошибки, а не в мнимый «успех».
    suspend fun emitError(message: String?) {
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(message ?: "Reflection failed"))
    }

    try {
      val context = prePromptAssembler.assemble(conversationId, userMessage)
      android.util.Log.d(TAG, "▶ reflection conv=$conversationId msg=\"${userMessage.take(60)}\"")

      // 1. DRAFT — обычный ReAct-цикл с полным набором тулов. context.history уже заканчивается
      // текущим сообщением пользователя (его персистит вызывающий до execute()).
      tap(AgentEvent.StateChanged(AgentState.STREAMING))
      tap(AgentEvent.TextDelta("✍️ **Черновик**\n"))
      val draftMessages = mutableListOf(ChatMessage(role = "system", content = context.systemPrompt))
      draftMessages += context.history.map { it.toChatMessage() }
      val draft = reactLoopRunner.run(
        conversationId, draftMessages, registry.getAllSchemas(), provider, emit = { tap(it) },
      )
      android.util.Log.d(TAG, "draft len=${draft.text.length}")
      // ReactLoopRunner на сбое стрима не бросает, а возвращает state=ERROR. Не проглатываем: как и
      // обычный чат-цикл, поднимаем ошибку наружу и не тратим critique/revise на пустой черновик.
      if (draft.state == AgentState.ERROR) {
        return@channelFlow emitError(draft.errorMessage)
      }

      // 2. CRITIQUE — один tool-less вызов: назвать конкретные слабые места черновика.
      tap(AgentEvent.TextDelta("\n\n🔍 **Критика**\n"))
      val critique = runCritic(userMessage, draft.text, provider) { tap(it) }

      // 3. REVISE — снова общий цикл (полный набор тулов, чтобы при необходимости закрыть ответ
      // карточкой), переписываем с учётом критики.
      tap(AgentEvent.TextDelta("\n\n---\n\n✨ **Итог**\n"))
      val reviseMessages = mutableListOf(ChatMessage(role = "system", content = context.systemPrompt))
      reviseMessages += context.history.map { it.toChatMessage() }
      reviseMessages += ChatMessage(role = "user", content = buildReviseInstruction(draft.text, critique))
      val revised = reactLoopRunner.run(
        conversationId, reviseMessages, registry.getAllSchemas(), provider, emit = { tap(it) },
      )
      android.util.Log.d(TAG, "✓ reflection done revisedLen=${revised.text.length}")
      if (revised.state == AgentState.ERROR) {
        return@channelFlow emitError(revised.errorMessage)
      }

      send(AgentEvent.StateChanged(AgentState.FINISHED))
      send(
        AgentEvent.Completed(
          AgentResult(
            text = transcript.toString(),
            // Карточки (render_card) берём только из фазы правки: черновик — выброшенный, его
            // карточки не должны отрисоваться как финальные. Остальной трейс черновика оставляем для
            // прозрачности (видно, чем агент пользовался на первом проходе). Критика тулы не вызывает.
            toolTrace = draft.toolTrace.filterNot { it.toolName == "render_card" } + revised.toolTrace,
            state = AgentState.FINISHED,
            // Число фаз (draft → critique → revise), не LLM-раундов — поле только для отладочного лога.
            iterations = 3,
          ),
        ),
      )
      // Как и обычный чат-ход: обновляем заголовок/резюме/теги диалога из лога (no-op, если коротко).
      runCatching { conversationAnnotator.annotate(conversationId) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      android.util.Log.e(TAG, "Reflection failed", e)
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(e.message ?: "Reflection failed"))
    }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

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

  private fun buildReviseInstruction(draft: String, critique: String): String = buildString {
    appendLine("Твой черновик ответа:")
    appendLine(draft)
    appendLine()
    appendLine("Критика черновика:")
    appendLine(critique)
    appendLine()
    appendLine(
      "Перепиши ответ, устранив замечания критики. Дай пользователю только финальный ответ на " +
        "его языке — без пересказа критики и без слова «черновик». Если для ответа нужна " +
        "визуализация (карточка, таблица, график) — вызови нужный инструмент заново: карточки из " +
        "черновика в финальный ответ не переносятся.",
    )
  }

  companion object {
    private const val TAG = "Reflection"

    private const val CRITIC_SYSTEM =
      "Ты — строгий критик-редактор. Тебе дают запрос пользователя и черновик ответа на него. " +
        "Коротко, 3–5 пунктов, укажи конкретные слабые места черновика: фактические ошибки, " +
        "упущенные части запроса, неясные места, лишнее. Сам ответ НЕ переписывай — только " +
        "замечания. Если черновик уже хорош, честно так и скажи. Пиши на языке пользователя."
  }
}
