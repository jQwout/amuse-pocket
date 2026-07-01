package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1

import com.aiopenpocket.agent.core.lesson.Lesson
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Message
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
  /** Bound mini-agent id; the coordinator routes through the supervisor, others scope the chat loop. */
  val agentId: String? = null,
  /** Display name shown in the chat header — the bound agent's name, else the conversation title. */
  val title: String? = null,
  val voiceState: VoiceInputState = VoiceInputState.Idle,
  val voiceError: String? = null,

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
