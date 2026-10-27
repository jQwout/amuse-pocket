package com.aiopenpocket.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The publish rate gate for streamed text (~20/sec): accumulate everything, publish no more often than the interval. */
class StreamingTextBufferTest {

  private var now = 100_000L
  private val buffer = StreamingTextBuffer(nowMs = { now }, minPublishIntervalMs = 50L)

  @Test
  fun `первая дельта публикуется сразу`() {
    assertEquals("a", buffer.append("a"))
  }

  @Test
  fun `дельты внутри интервала копятся молча, следующая публикация отдаёт всё накопленное`() {
    buffer.append("a")
    now += 10
    assertNull(buffer.append("b"))
    now += 50
    assertEquals("abc", buffer.append("c"))
  }

  @Test
  fun `flush отдаёт хвост, не прошедший гейт`() {
    buffer.append("a")
    now += 10
    buffer.append("b")
    assertEquals("ab", buffer.flush())
  }

  @Test
  fun `flush пустого буфера — null`() {
    assertNull(buffer.flush())
  }

  @Test
  fun `reset очищает буфер и гейт`() {
    buffer.append("a")
    buffer.reset()
    assertNull(buffer.flush())
    assertEquals("b", buffer.append("b"))
  }
}
