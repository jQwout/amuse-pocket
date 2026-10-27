package com.aiopenpocket.agent.core.llm

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // The broken chunk is skipped: no Error, the text before and after arrived, the turn is not cut off.
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
    // A reasoning model (deepseek-v4-flash) put everything into reasoning_content, and content never came.
    val sse = """
      data: {"choices":[{"delta":{"reasoning_content":"Думаю над "}}]}
      data: {"choices":[{"delta":{"reasoning_content":"ответом."}}]}
      data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    // Without a fallback this would be an empty answer; now we return the accumulated reasoning — marked as
    // thoughts rather than an answer, and with the reasoning flag for those who expect JSON.
    val deltas = events.filterIsInstance<StreamEvent.TextDelta>()
    assertEquals(REASONING_ONLY_PREFIX + "Думаю над ответом.", deltas.joinToString("") { it.content })
    assertTrue("размышление обязано быть помечено", deltas.all { it.reasoning })
    assertEquals("stop", (events.last() as StreamEvent.Finish).reason)
  }

  // --- finish_reason "length": the answer is cut off at max_tokens --------------------------------------

  @Test
  fun `length with only reasoning is an error, not a fake answer`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"reasoning":"Пользователь хочет, чтобы я"}}]}
      data: {"choices":[{"delta":{},"finish_reason":"length"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    val error = events.single() as StreamEvent.Error
    assertEquals(CODE_LENGTH_EMPTY, error.code)
    assertTrue(events.none { it is StreamEvent.TextDelta })
  }

  @Test
  fun `length with a half-written tool call does not execute it`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"alarm","arguments":"{\"action\":\"set_al"}}]}}]}
      data: {"choices":[{"delta":{},"finish_reason":"length"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    assertTrue("недописанный вызов ушёл на исполнение", events.none { it is StreamEvent.ToolCall })
    assertEquals(CODE_LENGTH_TOOL_CALL, (events.last() as StreamEvent.Error).code)
  }

  @Test
  fun `length on a complete text answer keeps it and marks it as cut`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"content":"Длинный ответ"}}]}
      data: {"choices":[{"delta":{},"finish_reason":"length"}]}
      data: [DONE]
    """.trimIndent()

    val events = parse(sse)

    val text = events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content }
    assertEquals("Длинный ответ$TRUNCATED_ANSWER_SUFFIX", text)
    assertTrue(events.none { it is StreamEvent.Error })
    assertEquals("length", (events.last() as StreamEvent.Finish).reason)
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

    // There is real content — the reasoning is not mixed into the answer.
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

  /**
   * A live gap: usage arrives in a SEPARATE final chunk whose `choices` is empty. Parsing started with the
   * line `choices.firstOrNull() ?: continue`, so exactly this chunk was thrown away — and the turn's
   * spending was unknown, although the provider had sent it.
   */
  @Test
  fun `usage arrives in a final chunk with empty choices and is captured`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"content":"Привет"}}]}
      data: {"choices":[{"delta":{},"finish_reason":"stop"}]}
      data: {"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":48,"cost":0.00031}}
      data: [DONE]
    """.trimIndent()

    val events = mutableListOf<StreamEvent>()
    val usage = consumeOpenAiSse(ByteReadChannel(sse), openAiCompatJson()) { events += it }

    assertEquals(1200, usage?.promptTokens)
    assertEquals(48, usage?.completionTokens)
    assertEquals(0.00031, usage?.cost ?: 0.0, 1e-9)
    // An empty choices does not break the rest of the parsing: the text and finish arrived as usual.
    assertEquals("Привет", events.filterIsInstance<StreamEvent.TextDelta>().joinToString("") { it.content })
    assertEquals("stop", (events.last() as StreamEvent.Finish).reason)
  }

  /**
   * A provider that sent no usage must differ from one that sent zeros: zero tokens is a claim that the
   * turn was free, while `null` is an honest "we don't know", on which the caller takes an estimate.
   */
  @Test
  fun `a stream without usage yields null, not zeroes`() = runTest {
    val sse = """
      data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}
      data: [DONE]
    """.trimIndent()

    val usage = consumeOpenAiSse(ByteReadChannel(sse), openAiCompatJson()) { }

    assertNull(usage)
  }
}
