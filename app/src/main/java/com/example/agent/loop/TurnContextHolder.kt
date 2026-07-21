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
 * Контекст текущего хода агента (диалог + мини-агент) для тулз, которым он нужен (heartbeat пишет
 * результат в «свой» диалог; [com.aiopenpocket.agent.tools.MemoryTools] namespace-скоупит
 * facts — Task 5). Хранится thread-local и прокидывается через coroutine-context:
 * [com.aiopenpocket.agent.loop.TurnManager] гоняет ходы РАЗНЫХ диалогов параллельно, поэтому
 * глобальный volatile отдал бы тулу чужой conversationId. [contextElement] привязывает значение к
 * корутине хода — тул-вызовы внутри неё читают именно свой контекст, на каком бы потоке ни возобновились.
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

  /** Обернуть ход: `withContext(holder.contextElement(convId, agent)) { reactLoopRunner.run(...) }`. */
  fun contextElement(conversationId: String?, agent: AgentDefinition?): CoroutineContext =
    threadLocal.asContextElement(Ctx(conversationId, agent))

  /** Прямая установка на текущем потоке — для тестов и синхронных путей. */
  fun set(conversationId: String?, agent: AgentDefinition?) {
    threadLocal.set(Ctx(conversationId, agent))
  }
}
