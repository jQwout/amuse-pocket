package com.aiopenpocket.agent.core.policy

// @trace approval-outcome-honesty-554a8c

/**
 * How one [PolicyDecision.AwaitApproval] ended. Three values rather than the `Boolean`
 * [ToolApprovalCoordinator.awaitApproval] used to return, because "the tool did not run" covers two
 * completely different facts about the person — and the app was telling them the wrong one.
 *
 * On Android 14, with the app minimized, the request expired after its timeout, the model was handed
 * «declined it or did not answer in time», and it wrote the user "Not sent — declined in chat." The
 * user had declined nothing; they had not even seen the card. A single `false` made that sentence
 * unavoidable: the text that explains the outcome cannot be more precise than the outcome itself. So
 * the outcome carries the distinction, and [ApprovalResultText] gives each case its own words.
 */
enum class ApprovalOutcome {
  /** The person tapped "Allow". */
  APPROVED,

  /** The person tapped "Decline" — a real decision, and the model may say so. */
  DECLINED,

  /**
   * Nobody answered: the timeout elapsed, or the run was torn down from outside
   * ([ToolApprovalCoordinator.cancelRun]/[ToolApprovalCoordinator.abandonAllPending]). Says nothing
   * about what the person wanted — they may never have seen the request.
   */
  UNANSWERED,
}

/**
 * What the MODEL is told about a request that did not end in "Allow" — English, like the rest of the
 * harness's tool-result text (the tools themselves answer the model in Russian, see AGENTS.md; this
 * is the loop speaking, not a tool).
 *
 * Two separate functions on purpose, each a named thing a test can pin down: as long as the wording
 * lived inline in one `else` branch of
 * [com.aiopenpocket.agent.loop.ReactLoopRunner], nothing could tell the two cases apart, and the
 * single text had to hedge with "or" — which the model resolved, in front of the user, to the
 * accusation. [unanswered] deliberately contains no word for refusal in any language: the model
 * reads the salient word, not the negation around it.
 */
object ApprovalResultText {

  /** The person actually said no. Naming it as their decision keeps the model from blaming a setting. */
  fun declined(toolName: String): String =
    "The user saw the request for '$toolName' in the chat and declined it. This is the user's own " +
      "decision, not a missing permission or setting: do not suggest enabling anything and do not call " +
      "it again this turn. Say briefly that it was not done because they declined it; offer to do it if " +
      "they ask again."

  /**
   * Nobody answered. The one thing the model must not do here is turn silence into the user's choice.
   *
   * Nor may it promise a follow-through the app does not have: once the window closes the approval card
   * is gone, nothing waits in the background, and a later "yes" has nowhere to land. An earlier wording
   * told the model to say "it will happen as soon as they confirm" — the same kind of untrue sentence as
   * "you declined it", only pointing the other way. The person has to ask again, so the model says so.
   */
  fun unanswered(toolName: String): String =
    "The approval request for '$toolName' expired with no answer, so nothing was done. The app may " +
      "have been in the background and the user may never have seen the request: this is not a choice " +
      "they made, and do not blame them for it. Do not call the tool again this turn. Say plainly that " +
      "it is not done yet because the request went unanswered, and offer to do it when they ask again."
}
