package com.aiopenpocket.agent.core.llm

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers [consumeOpenAiSse]: the OpenAI-compatible SSE parsing that used to be the riskiest, untested part. */
class OpenAiSseParserTest {

  private suspend fun parse(sse: String): List<StreamEvent> {
    val events = mutableListOf<StreamEvent>()
    consumeOpenAiSse(ByteReadChannel(sse), openAiCompatJson()) { events += it }
    return events
  }

  @Test
  fun `text deltas are forwarded then finish`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"content":"Hello"}}]}
      data: {"choices":[{"delta":{"content":" world"}}]}
      data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    assertEquals("Hello", (events[0] as StreamEvent.TextDelta).content)
    assertEquals(" world", (events[1] as StreamEvent.TextDelta).content)
    assertEquals("stop", (events.last() as StreamEvent.Finish).reason)
  }

  @Test
  fun `a single malformed chunk is skipped, not fatal`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"content":"before"}}]}
      data: {this is not valid json}
      data: {"choices":[{"delta":{"content":"after"},"finish_reason":"stop"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    // Битый чанк пропущен: никаких Error, текст до и после дошёл, ход не оборван.
    assertTrue("parse error must not surface as a stream Error", events.none { it is StreamEvent.Error })
    val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content }
    assertEquals("beforeafter", text)
    assertEquals("stop", (events.last() as StreamEvent.Finish).reason)
  }

  @Test
  fun `tool call fragments are accumulated and emitted once`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"render_card","arguments":"{\"template\":"}}]}}]}
      data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"timer\"}"}}]}}]}
      data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    val calls = events.filterIsInstance<StreamEvent.ToolCall>()
    assertEquals(1, calls.size)
    assertEquals("render_card", calls[0].name)
    assertEquals("call_1", calls[0].id)
    assertEquals("timer", (calls[0].arguments["template"] as JsonPrimitive).content)
    assertEquals("tool_calls", (events.last() as StreamEvent.Finish).reason)
  }

  @Test
  fun `two parallel tool calls are both emitted, keyed by index`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"first","arguments":"{}"}}]}}]}
      data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"second","arguments":"{}"}}]}}]}
      data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    val calls = events.filterIsInstance<StreamEvent.ToolCall>()
    assertEquals(listOf("first", "second"), calls.map { it.name })
    assertEquals(listOf("a", "b"), calls.map { it.id })
  }

  @Test
  fun `a stream closing without finish_reason defaults to stop`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"content":"hi"}}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    assertEquals("stop", (events.last() as StreamEvent.Finish).reason)
  }

  @Test
  fun `malformed tool arguments produce emptyMap ToolCall, not an error`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"do_thing","arguments":"{invalid json"}}]}}]}
      data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    val calls = events.filterIsInstance<StreamEvent.ToolCall>()
    assertEquals(1, calls.size)
    assertEquals("do_thing", calls[0].name)
    assertTrue("malformed args must produce emptyMap", calls[0].arguments.isEmpty())
    assertTrue("no Error event on malformed args", events.none { it is StreamEvent.Error })
  }

  @Test
  fun `reasoning_content is used as the answer when content stays empty`() = runTest {
    // Reasoning-модель (deepseek-v4-flash) уложила всё в reasoning_content, content так и не пришёл.
    val sse = """
      data: {"choices":[{"delta":{"reasoning_content":"Думаю над "}}]}
      data: {"choices":[{"delta":{"reasoning_content":"ответом."}}]}
      data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    // Без фолбэка тут был бы пустой ответ; теперь отдаём накопленное размышление как текст.
    val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content }
    assertEquals("Думаю над ответом.", text)
    assertEquals("stop", (events.last() as StreamEvent.Finish).reason)
  }

  @Test
  fun `reasoning_content is ignored when real content is present`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"reasoning_content":"Сначала подумаю."}}]}
      data: {"choices":[{"delta":{"content":"Привет!"}}]}
      data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    // Есть настоящий content — размышление не подмешиваем в ответ.
    val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content }
    assertEquals("Привет!", text)
  }

  @Test
  fun `colon-prefixed keep-alive lines are silently ignored`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"content":"hi"}}]}
      : keep-alive
      data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    assertTrue("keep-alive must not surface as Error", events.none { it is StreamEvent.Error })
    val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content }
    assertEquals("hi", text)
  }
}
