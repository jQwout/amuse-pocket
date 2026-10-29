package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import com.aiopenpocket.agent.loop.AgentMode
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * How hard a reasoning model should think. Without it the model picks its own level, and reasoning
 * tokens are billed as output AND eat the same `max_tokens` budget as the answer — so a lesson
 * question answered at the model's default effort can burn the whole budget on thinking
 * (dev.meta.ai/docs/reasoning: "use the lowest effort that gives acceptable results").
 *
 * Only the values this app uses. `none` is deliberately absent: Muse Wonder and Spark answer it with
 * HTTP 400.
 */
enum class ReasoningEffort(val wire: String) {
  LOW("low"),
  MEDIUM("medium"),
  HIGH("high"),
}

/** The effort a turn in [mode] asks for: a lesson answer is cheap, planning and self-critique are not. */
fun reasoningEffortFor(mode: AgentMode): ReasoningEffort = when (mode) {
  AgentMode.TUTOR -> ReasoningEffort.LOW
  AgentMode.CHAT, AgentMode.GOAL -> ReasoningEffort.MEDIUM
  AgentMode.PLAN, AgentMode.REFLECT -> ReasoningEffort.HIGH
}

/**
 * Carries the effort through the coroutine context instead of a parameter on [LlmClient.streamChat].
 *
 * Why not a parameter: the model is called from seven places (the loop, the planner, the verifier, the
 * reflection orchestrator, the annotator…), and `PlanStepRunner` mirrors `ReactLoopRunner.run`'s
 * signature exactly — a new parameter would ripple through all of them to deliver one value that is
 * decided in a single place, where the turn's mode is dispatched. A call outside any turn (no element)
 * sends no effort, and the model keeps its own default as before.
 */
class ReasoningEffortContext(val effort: ReasoningEffort) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<ReasoningEffortContext>
}

/**
 * How the effort is spelled on the wire, per route. The two APIs are not the same:
 * - Meta Model API: a top-level `reasoning_effort` (dev.meta.ai/docs/reasoning);
 * - OpenRouter: a nested `reasoning: {effort}` object (openrouter.ai/docs/guides/best-practices/reasoning-tokens).
 *   Its page documents no top-level `reasoning_effort`, so it is not sent there.
 */
internal data class ReasoningRequestFields(
  val reasoningEffort: String? = null,
  val reasoning: ReasoningObject? = null,
)

@kotlinx.serialization.Serializable
internal data class ReasoningObject(val effort: String)

/** Null for a model that is not a reasoning model: sending the field to one risks an HTTP 400. */
internal fun reasoningFieldsFor(config: ProviderConfig, effort: ReasoningEffort?): ReasoningRequestFields? {
  if (effort == null || !config.reasoning) return null
  return when (config.name) {
    LlmProvider.META -> ReasoningRequestFields(reasoningEffort = effort.wire)
    LlmProvider.OPENROUTER -> ReasoningRequestFields(reasoning = ReasoningObject(effort.wire))
  }
}
