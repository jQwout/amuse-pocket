package com.aiopenpocket.ui.chat

// @trace streaming-llm-chat-6595a1
// @trace user-agent-builder-848153
// @trace structured-plan-engine-907066

import com.aiopenpocket.agent.loop.AgentMode

sealed class ChatIntent {
  data class UpdateInput(val text: String) : ChatIntent()
  data object SendMessage : ChatIntent()
  data object CancelRequest : ChatIntent()
  data object ToggleToolTrace : ChatIntent()
  /** Delete a message (the confirmation happens in the UI, before this intent is sent). */
  data class DeleteMessage(val messageId: String) : ChatIntent()
  data object RetryRequest : ChatIntent()
  data object ClearError : ChatIntent()
  data object NavigateBack : ChatIntent()
  data object ToggleVoiceInput : ChatIntent()
  /** Cancel recording: drop the recognition without inserting text (the trash button in the recording bar). */
  data object CancelVoiceInput : ChatIntent()
  data object ClearVoiceError : ChatIntent()

  /** Change the agent mode (Chat/Plan/Goal). Replaces the old plan-mode boolean toggle. */
  data class SetMode(val mode: AgentMode) : ChatIntent()

  /**
   * Task 5 (structured-plan-engine-907066): typed plan commands — they go straight to
   * `PlanCommands`/`PlanRepository`, WITHOUT a magic chat message ("I approve the plan, run it step by
   * step" is no longer written into the history anywhere — see the removed `TurnTexts.APPROVE_PLAN_MESSAGE`).
   * [version] is exactly the version the user saw in PlanApprovalBar/PlanCard: `PlanCommands` rejects a
   * command on an outdated (no longer the current pending) version. Approval starts execution in the
   * background: PlanScheduler arms a typed heartbeat for the next step (without a schedule — right
   * away), and the step's answer lands in this same chat.
   */
  data class ApprovePlan(val planId: String, val version: Int) : ChatIntent()
  /**
   * A request for changes: the plan goes to DRAFT, and a PLAN turn starts right away with the user's
   * comment (or "Rebuild the plan with the changes") — otherwise the card disappeared and nobody waited
   * for a new version.
   */
  data class RequestPlanChanges(val planId: String, val version: Int, val feedback: String) : ChatIntent()
  /** Task 5: pause an ACTIVE plan (and its typed heartbeat/schedule — see PlanScheduleSync). */
  data class PausePlan(val planId: String) : ChatIntent()
  /** Task 5: resume a PAUSED plan. */
  data class ResumePlan(val planId: String) : ChatIntent()
  /** Task 5: cancel the plan — a terminal status. */
  data class CancelPlan(val planId: String) : ChatIntent()

  /**
   * USER_CONFIRMATION: the user confirms a step blocked on a confirmation criterion (the design fork
   * "re-check only, no re-run"). Goes to `PlanCommands.confirmStep`: writes the confirmation evidence and
   * RE-checks the criteria WITHOUT running the step again (COMPLETED if the last required criterion is
   * closed; otherwise the step is just unblocked to READY).
   */
  data class ConfirmPlanStep(val planId: String, val stepId: String) : ChatIntent()

  /**
   * Plan mode: "Edit" is a purely local UI gesture (it hides the approval bar in ChatUiState and lets the
   * user write in the chat); unlike [RequestPlanChanges] it does not go to PlanRepository.
   */
  data object EditPlan : ChatIntent()

  /** Lesson: show the next step (or finish if the step was the last). */
  data object AdvanceLesson : ChatIntent()
  /** Lesson: the quiz is passed — show the completion card. */
  data object CompleteLessonQuiz : ChatIntent()
  /** Lesson: the person picked (or cleared, [state] = null) quiz answers — persist them on the message. */
  data class SaveCardState(val messageId: String, val state: String?) : ChatIntent()

  /** "Draw it for me" on a lesson's image step: redraw [imagePrompt] for the profile and the language. */
  data class PersonalizeLessonImage(val imagePrompt: String, val caption: String) : ChatIntent()

  /** The "video" lesson step: the user filmed or picked a clip [video] — hand it to Muse Spark with [videoPrompt]. */
  data class AnalyzeLessonVideo(val videoPrompt: String, val video: android.net.Uri) : ChatIntent()

  /**
   * The "segment" lesson step: the learner chose a photo [photo] — hand it to SAM 3.1 to outline [concept].
   * [cardPrompt] is the card's default prompt: it keys the card's "looking…" state, like [AnalyzeLessonVideo].
   */
  data class SegmentLessonPhoto(val cardPrompt: String, val concept: String, val photo: android.net.Uri) : ChatIntent()

  /** Lesson: a free question to the LLM right during the lesson (the "Ask" button). */
  data class AskDuringLesson(val text: String) : ChatIntent()

  /**
   * The tool-approval bar (Task 9): the user allowed/denied `state.pendingToolApproval`. Neither carries
   * parameters — they resolve exactly the request that is in the state right now (see ChatViewModel).
   */
  data object ApproveToolCall : ChatIntent()
  data object DenyToolCall : ChatIntent()
}
