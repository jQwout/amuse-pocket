package com.aiopenpocket.agent.loop

// @trace heartbeat-napominalki-agenta-5f0442
// @trace user-agent-builder-848153

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Context of the current agent turn (conversation + mini-agent) for the tools that need it (heartbeat
 * writes its result into "its own" conversation; [com.aiopenpocket.agent.tools.MemoryTools] scopes
 * facts by namespace — Task 5). It is stored thread-local and carried through the coroutine context:
 * [com.aiopenpocket.agent.loop.TurnManager] runs turns of DIFFERENT conversations in parallel, so a
 * global volatile would hand a tool someone else's conversationId. [contextElement] binds the value to
 * the turn's coroutine — tool calls inside it read their own context, whatever thread they resume on.
 *
 * [Ctx] carries the resolved [AgentDefinition] snapshot itself (not just its id): the orchestrator
 * already resolved it once via `PrePromptAssembler.PromptContext.agent`, and tools must read that
 * SAME snapshot — re-resolving by id inside a tool would risk observing a concurrent edit/publish
 * mid-turn, and would need its own `AgentResolver` dependency just to get back what the caller
 * already had.
 */
@SingleIn(AppScope::class)
@Inject
class TurnContextHolder() {
  data class Ctx(val conversationId: String?, val agent: AgentDefinition?)

  private val threadLocal = ThreadLocal<Ctx?>()

  val conversationId: String? get() = threadLocal.get()?.conversationId
  val agent: AgentDefinition? get() = threadLocal.get()?.agent

  /** Convenience for callers that only need the id (e.g. [com.aiopenpocket.agent.tools.HeartbeatTool] persisting `HeartbeatEntity.agentId`). */
  val agentId: String? get() = agent?.id

  /** Wrap a turn: `withContext(holder.contextElement(convId, agent)) { reactLoopRunner.run(...) }`. */
  fun contextElement(conversationId: String?, agent: AgentDefinition?): CoroutineContext =
    threadLocal.asContextElement(Ctx(conversationId, agent))

  /** Sets the value directly on the current thread — for tests and synchronous paths. */
  fun set(conversationId: String?, agent: AgentDefinition?) {
    threadLocal.set(Ctx(conversationId, agent))
  }
}
