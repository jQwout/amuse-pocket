package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.lesson.Lesson
import com.aiopenpocket.agent.core.llm.LlmContextConfig
import com.aiopenpocket.agent.core.memory.model.Message
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.core.identity.ChatFontScale
import com.aiopenpocket.voice.input.VoiceInputState

data class ResponseStats(
  val responseTimeMs: Long,
  val completionTokens: Int,
  val contextTokensUsed: Int,
  val contextBudget: Int = LlmContextConfig.HISTORY_TOKEN_BUDGET,
)

/**
 * Task 5 (structured-plan-engine-907066): identifies the exact [com.aiopenpocket.agent.core.plan.model.PlanVersion]
 * a `PlanApprovalBar`/PlanCard is currently showing the user, so `ChatIntent.ApprovePlan`/
 * `RequestPlanChanges` always target that precise version — never "whatever is pending now".
 * Replaces the Phase A per-turn `planAwaitingApproval: Boolean`: whether a plan awaits a decision is
 * no longer a byproduct of the last chat turn, it is `PlanRepository`'s own persisted state (see
 * `PlanCommands`). Populated by `ChatViewModel.observePendingPlanApproval` (Task 10) — a reactive
 * subscription to `PlanRepository.observeByConversation`, not a one-shot turn event — so it is
 * always in sync with the repository regardless of which turn/path last called `saveProposal`.
 */
data class PendingPlanApproval(val planId: String, val version: Int)

data class ChatUiState(
  val conversationId: String? = null,
  val messages: List<Message> = emptyList(),
  /** True while the DB has messages older than the current window (see pagination in ChatViewModel). */
  val hasMoreOlder: Boolean = false,
  /**
   * Indexes of the messages (in [messages]) containing the current search query. Computed in the
   * ViewModel with a debounce on Dispatchers.Default — the scan used to run in composition on every typed
   * character (tens of ms on a long history right on Main).
   */
  val searchMatches: List<Int> = emptyList(),
  val isLoading: Boolean = false,
  // The streaming text lives in a separate flow, ChatViewModel.streamingText (not in this state), so token
  // deltas do not rebuild the whole screen — only the stream bubble.
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
   * A tool call in THIS conversation's active run is waiting for the user's approval (a risky
   * DESTRUCTIVE/EXTERNAL call, or a `BEFORE_TOOL` hook that asked for it, Task 9) — null whenever
   * nothing is waiting. [ChatViewModel] derives this from `ToolApprovalCoordinator.pending`, filtered
   * to this screen's own conversation id, so a pending approval in a different chat never leaks in
   * here. ChatScreen shows it as `ToolApprovalBar` ("The agent needs permission" → "Allow"/"Decline").
   */
  val pendingToolApproval: ToolApprovalRequest? = null,

  // --- The agent mode (plan/goal mode) — an ephemeral UI flag of the chat screen. Replaces the old planMode boolean. ---
  val agentMode: AgentMode = AgentMode.CHAT,
  /** Plan mode (Task 5): the plan is shown, waiting for "Run"/"Edit" — null while there is nothing to decide. */
  val pendingPlanApproval: PendingPlanApproval? = null,
  /**
   * Task 12 (structured-plan-engine-907066): id of THIS conversation's currently ACTIVE/PAUSED plan,
   * if any — populated by `ChatViewModel.observeActivePlan`, the same reactive
   * `PlanRepository.observeByConversation` subscription style as [pendingPlanApproval] but filtered to
   * the opposite pair of statuses. Drives `PlanCard`'s compact-progress branch in the chat stream once
   * a plan has been approved and GOAL has started executing it; [pendingPlanApproval] alone never
   * covers that case since it only ever holds an AWAITING_APPROVAL plan.
   */
  val activePlanId: String? = null,
  /** The goal-mode iteration limit (from settings). */
  val goalMaxIterations: Int = 3,
  /** The message font size preset — from Settings, applied to the bubbles' text. */
  val chatFontScale: ChatFontScale = ChatFontScale.DEFAULT,

  // --- A lesson driven by the chat controller by the JSON contract ---
  /** True if this chat is a JSON lesson (assets/lessons/{title}.json exists). */
  val lessonMode: Boolean = false,
  val lesson: Lesson? = null,
  /** How many lesson steps have been published already (the next to show is steps[lessonStepIndex]). */
  val lessonStepIndex: Int = 0,
  /** True when the lesson completion card is shown. */
  val lessonFinished: Boolean = false,
  /** Lesson images currently being redrawn "for me" (by imagePrompt). */
  val personalizingImagePrompts: Set<String> = emptySet(),
  /** "video" steps whose clips the model is watching right now (by videoPrompt). While not empty, "Next" is hidden. */
  val analyzingVideoPrompts: Set<String> = emptySet(),
  /** "segment" steps whose photos SAM is outlining right now (by the card's default prompt). While not empty, "Next" is hidden. */
  val segmentingPrompts: Set<String> = emptySet(),
  /**
   * What went wrong on the lesson's CURRENT step: no Meta key for the "segment" step, a clip the model could not
   * read, a picture that did not redraw. It has its own slot, apart from [error], because the two have different
   * owners. [error] is the failed turn's: it is re-derived from the turn session on every change of the message
   * list, and its "Retry" belongs to that turn. A step problem belongs to the step: it is set by the step's
   * action and goes away only when the step is tried again or the lesson moves on ([withLessonCursor]).
   * Sharing one slot made each owner's write undo the other's, depending on which flow was delivered last.
   */
  val lessonStepProblem: String? = null,
) {
  /**
   * The lesson cursor moved: the next step was shown, or the completion card. The step's problem
   * ([lessonStepProblem]) goes with the step it was about; left drawn, "This step needs a Meta Model API key"
   * stayed under the privacy paragraph, under the quiz and under the "Lesson complete" card.
   *
   * The failed turn's [error] and its [canRetry] are NOT touched. A question asked mid-lesson that failed is
   * still unanswered after "Next", and "Retry" is the only way back to it; the session re-derives [error] on
   * every change of the message list anyway, so clearing it here would be undone a moment later or not,
   * depending on delivery order.
   */
  fun withLessonCursor(
    stepIndex: Int = lessonStepIndex,
    finished: Boolean = lessonFinished,
  ): ChatUiState =
    copy(lessonStepIndex = stepIndex, lessonFinished = finished, lessonStepProblem = null)

  companion object {
    /**
     * A fresh state for conversation [id]: the conversation fields are reset, but the pref-derived ones
     * ([chatFontScale], [goalMaxIterations] — from Settings) are KEPT. They are set by the reactive
     * collector in ChatViewModel.init, and a DataStore Flow does not re-emit an unchanged value — without
     * carrying them over explicitly they would be stuck at the default after the very first conversation
     * switch (the "changing the chat font size does not work" bug).
     */
    fun resetFor(
      id: String,
      chatFontScale: ChatFontScale,
      goalMaxIterations: Int,
    ) = ChatUiState(
      conversationId = id,
      chatFontScale = chatFontScale,
      goalMaxIterations = goalMaxIterations,
    )
  }
}
