package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.lesson.Lesson
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.voice.input.VoiceInputState

data class ResponseStats(
  val responseTimeMs: Long,
  val completionTokens: Int,
  val contextTokensUsed: Int,
  val contextBudget: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
)

data class ChatUiState(
  val conversationId: String? = null,
  val messages: List<Message> = emptyList(),
  /** True, пока в БД есть сообщения старше текущего окна (см. пагинацию в ChatViewModel). */
  val hasMoreOlder: Boolean = false,
  val inputText: String = "",
  /**
   * Индексы сообщений (в [messages]), содержащих текущий поисковый запрос. Считаются во
   * ViewModel с дебаунсом на Dispatchers.Default — раньше скан шёл в композиции на каждый
   * введённый символ (десятки мс на длинной истории прямо на Main).
   */
  val searchMatches: List<Int> = emptyList(),
  val isLoading: Boolean = false,
  // Стриминговый текст живёт в отдельном потоке ChatViewModel.streamingText (не в этом state),
  // чтобы дельты токенов не пересобирали весь экран — только пузырь стрима.
  val toolTrace: List<ToolTrace> = emptyList(),
  val error: String? = null,
  /** True when the last failed turn can be retried (we still have the last user message). */
  val canRetry: Boolean = false,
  val showToolTrace: Boolean = false,
  val lastResponseStats: ResponseStats? = null,
  /** True when this conversation is a roadmap lesson — enables interactive cards. */
  val isTutorial: Boolean = false,
  /** Roadmap level id (the tutorial conversation title), used to mark completion. */
  val levelId: String? = null,
  /** Bound mini-agent id — scopes the chat loop's tool set and skill access (see PrePromptAssembler). */
  val agentId: String? = null,
  /** Resolved snapshot of the bound agent (see AgentResolver) — null for a plain, agent-less chat. */
  val agent: AgentDefinition? = null,
  /** Display name shown in the chat header — the bound agent's name, else the conversation title. */
  val title: String? = null,
  val voiceState: VoiceInputState = VoiceInputState.Idle,
  val voiceError: String? = null,

  /**
   * A `BEFORE_TOOL` hook required approval for a tool call in THIS conversation's active run (Task 9)
   * — null whenever nothing is waiting. [ChatViewModel] derives this from
   * `ToolApprovalCoordinator.pending`, filtered to this screen's own conversation id, so a pending
   * approval in a different chat never leaks in here. The tool-approval modal/bar itself (tool name,
   * risk, arguments, approve/deny buttons) is not wired into ChatScreen yet — see the TODO left there.
   */
  val pendingToolApproval: ToolApprovalRequest? = null,

  // --- Режим агента (план-/гоал-мод) — эфемерный UI-флаг экрана чата. Заменяет прежний planMode-boolean. ---
  val agentMode: AgentMode = AgentMode.CHAT,
  /** План-мод: план показан, ждём «Выполнить»/«Поправить». */
  val planAwaitingApproval: Boolean = false,
  /** Лимит витков гоал-мода (из настроек). */
  val goalMaxIterations: Int = 3,

  // --- Урок, который драйвит чат-контроллер по JSON-контракту ---
  /** True, если этот чат — JSON-урок (есть assets/lessons/{title}.json). */
  val lessonMode: Boolean = false,
  val lesson: Lesson? = null,
  /** Сколько шагов урока уже опубликовано (следующий к показу — steps[lessonStepIndex]). */
  val lessonStepIndex: Int = 0,
  /** True, когда показана карточка завершения урока. */
  val lessonFinished: Boolean = false,
) {
  companion object {
    fun resetFor(id: String) = ChatUiState(conversationId = id)
  }
}
