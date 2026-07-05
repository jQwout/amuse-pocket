package com.aiopenpocket.agent.loop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnContextHolderTest {

  @Test
  fun `empty by default`() {
    val holder = TurnContextHolder()
    assertNull(holder.conversationId)
    assertNull(holder.agentId)
  }

  @Test
  fun `set seeds current thread`() {
    val holder = TurnContextHolder()
    holder.set("conv1", "reminders")
    assertEquals("conv1", holder.conversationId)
    assertEquals("reminders", holder.agentId)
  }

  // Гарантия против гонки: TurnManager гоняет ходы разных диалогов параллельно. Каждый ход в своей
  // обёртке contextElement должен видеть СВОЙ conversationId, даже перескочив на другой поток.
  @Test
  fun `contextElement isolates concurrent turns`() = runTest {
    val holder = TurnContextHolder()
    val results = listOf("A" to "budget", "B" to "diary").map { (conv, agent) ->
      async(Dispatchers.Default) {
        withContext(holder.contextElement(conv, agent)) {
          yield() // спровоцировать возможный перескок потока внутри хода
          holder.conversationId to holder.agentId
        }
      }
    }.awaitAll()
    assertEquals(listOf("A" to "budget", "B" to "diary"), results)
    assertNull(holder.conversationId) // вне обёртки контекст снова пуст
  }
}
