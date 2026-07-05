package com.aiopenpocket.agent.loop

// @trace heartbeat-napominalki-agenta-5f0442

import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Контекст текущего хода агента (диалог + мини-агент) для тулз, которым он нужен (heartbeat пишет
 * результат в «свой» диалог). Хранится thread-local и прокидывается через coroutine-context:
 * [com.aiopenpocket.agent.loop.TurnManager] гоняет ходы РАЗНЫХ диалогов параллельно, поэтому
 * глобальный volatile отдал бы тулу чужой conversationId. [contextElement] привязывает значение к
 * корутине хода — тул-вызовы внутри неё читают именно свой контекст, на каком бы потоке ни возобновились.
 */
@SingleIn(AppScope::class)
class TurnContextHolder @Inject constructor() {
  data class Ctx(val conversationId: String?, val agentId: String?)

  private val threadLocal = ThreadLocal<Ctx?>()

  val conversationId: String? get() = threadLocal.get()?.conversationId
  val agentId: String? get() = threadLocal.get()?.agentId

  /** Обернуть ход: `withContext(holder.contextElement(convId, agentId)) { reactLoopRunner.run(...) }`. */
  fun contextElement(conversationId: String?, agentId: String?): CoroutineContext =
    threadLocal.asContextElement(Ctx(conversationId, agentId))

  /** Прямая установка на текущем потоке — для тестов и синхронных путей. */
  fun set(conversationId: String?, agentId: String?) {
    threadLocal.set(Ctx(conversationId, agentId))
  }
}
