package com.aiopenpocket.agent.loop

import com.aiopenpocket.agent.core.agent.MiniAgent
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.llm.ChatMessage
import com.aiopenpocket.agent.core.llm.FunctionSchema
import com.aiopenpocket.agent.core.llm.LlmClient
import com.aiopenpocket.agent.core.llm.LlmProvider
import com.aiopenpocket.agent.core.llm.StreamEvent
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.string
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.prompt.ConversationAnnotator
import com.aiopenpocket.agent.core.prompt.PrePromptAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope

/**
 * Multi-agent supervisor (L2): the coordinator agent decides which specialist [MiniAgent] should
 * handle a sub-task and calls the virtual `delegate` tool. The orchestrator — not [ToolRegistry] —
 * fulfils that call by running the chosen specialist as a scoped [ReactLoopRunner] pass (its prompt +
 * its tools) and feeding the result back as the tool message, so the coordinator can delegate again
 * or answer. Specialists are the same mini-agents you can also talk to directly (L1).
 */
@SingleIn(AppScope::class)
class SupervisorOrchestrator @Inject constructor(
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
    val transcript = StringBuilder()
    val tap: suspend (AgentEvent) -> Unit = { ev ->
      if (ev is AgentEvent.TextDelta) transcript.append(ev.content)
      send(ev)
    }

    try {
      val context = prePromptAssembler.assemble(conversationId, userMessage)
      android.util.Log.d(TAG, "▶ supervisor conv=$conversationId msg=\"${userMessage.take(60)}\"")
      // История диалога уже заканчивается текущим сообщением пользователя (его сохранили до
      // маршрутизации), поэтому отдельным user-сообщением его НЕ добавляем — иначе уйдёт дважды.
      // Тот же приём, что в AgentLoopOrchestrator, даёт координатору память о прошлых ходах.
      val messages = mutableListOf(
        ChatMessage(role = "system", content = context.systemPrompt + "\n\n" + rosterBlock()),
      )
      messages += context.history.map { it.toChatMessage() }
      val toolTrace = mutableListOf<ToolTrace>()

      var rounds = 0
      for (iteration in 1..MAX_ROUNDS) {
        rounds = iteration
        var accumulated = ""
        var finished = false
        var streamError: String? = null
        val pending = mutableListOf<StreamEvent.ToolCall>()
        tap(AgentEvent.StateChanged(AgentState.STREAMING))

        llmClient.streamChat(messages, listOf(DELEGATE), provider).collect { chunk ->
          when (chunk) {
            is StreamEvent.TextDelta -> {
              accumulated += chunk.content
              tap(AgentEvent.TextDelta(chunk.content))
            }
            is StreamEvent.ToolCall -> pending += chunk
            is StreamEvent.Finish -> if (pending.isEmpty()) finished = true
            is StreamEvent.Error -> streamError = chunk.message
          }
        }

        if (streamError != null) {
          send(AgentEvent.StateChanged(AgentState.ERROR))
          send(AgentEvent.Error(streamError ?: "Unknown error"))
          return@channelFlow
        }
        if (finished) break
        if (pending.isEmpty()) continue

        messages += ChatMessage(
          role = "assistant",
          content = accumulated.ifBlank { null },
          toolCalls = pending.map { tc ->
            ChatMessage.ToolCallData(
              id = tc.id,
              function = ChatMessage.ToolCallData.FunctionCall(
                name = tc.name,
                arguments = Json.encodeToString(tc.arguments),
              ),
            )
          },
        )

        for (tc in pending) {
          val resultText = if (tc.name == "delegate") {
            val agentId = tc.arguments.string("agent_id")
            val task = tc.arguments.string("task").orEmpty()
            val specialist = MiniAgentCatalog.get(agentId)?.takeUnless { it.isSupervisor }
            tap(AgentEvent.StateChanged(AgentState.EXECUTING_TOOL))
            tap(AgentEvent.TextDelta("\n\n**→ ${specialist?.name ?: agentId}:** $task\n"))
            if (specialist == null) {
              android.util.Log.w(TAG, "delegate → unknown agent_id=$agentId")
              "Нет агента с id=$agentId. Доступные: ${MiniAgentCatalog.specialistIds.joinToString()}"
            } else {
              android.util.Log.d(TAG, "delegate → ${specialist.id}: \"${task.take(60)}\"")
              val sub = runSpecialist(conversationId, specialist, task, provider) { tap(it) }
              toolTrace += sub.toolTrace
              android.util.Log.d(TAG, "← ${specialist.id} done state=${sub.state} (${sub.text.length} chars)")
              if (sub.state == AgentState.ERROR) "Ошибка агента: ${sub.errorMessage}" else sub.text
            }
          } else {
            "Неизвестный инструмент: ${tc.name}"
          }
          messages += ChatMessage(role = "tool", toolCallId = tc.id, content = resultText)
        }
      }

      android.util.Log.d(TAG, "✓ supervisor done rounds=$rounds")
      send(AgentEvent.StateChanged(AgentState.FINISHED))
      send(
        AgentEvent.Completed(
          AgentResult(
            text = transcript.toString(),
            toolTrace = toolTrace,
            state = AgentState.FINISHED,
            iterations = rounds,
          ),
        ),
      )
      // Как и обычный чат-ход: обновляем заголовок/резюме/теги диалога из лога (no-op, если коротко).
      runCatching { conversationAnnotator.annotate(conversationId) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      android.util.Log.e("Supervisor", "Supervisor failed", e)
      send(AgentEvent.StateChanged(AgentState.ERROR))
      send(AgentEvent.Error(e.message ?: "Supervisor failed"))
    }
  }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

  /** Run one specialist as a scoped pass of the shared loop: its own prompt + only its tools. */
  private suspend fun runSpecialist(
    conversationId: String,
    agent: MiniAgent,
    task: String,
    provider: LlmProvider,
    emit: suspend (AgentEvent) -> Unit,
  ): AgentResult {
    val messages = mutableListOf(
      ChatMessage(role = "system", content = agent.systemPrompt),
      ChatMessage(role = "user", content = task),
    )
    return reactLoopRunner.run(conversationId, messages, registry.getSchemasFor(agent.toolNames), provider, emit)
  }

  private fun rosterBlock(): String = buildString {
    appendLine("## Команда специалистов")
    MiniAgentCatalog.specialists.forEach { appendLine("- ${it.id}: ${it.name} — ${it.description}") }
  }

  companion object {
    private const val TAG = "Supervisor"
    private const val MAX_ROUNDS = 6

    private val DELEGATE = toolSchema(
      name = "delegate",
      description = "Поручи подзадачу агенту-специалисту и получи его результат.",
    ) {
      string("agent_id", "ID агента-специалиста из списка команды.", required = true, enum = MiniAgentCatalog.specialistIds)
      string("task", "Подзадача для специалиста на естественном языке.", required = true)
    }
  }
}
