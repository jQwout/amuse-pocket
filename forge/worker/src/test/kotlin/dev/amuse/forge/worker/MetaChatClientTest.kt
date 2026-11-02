package dev.amuse.forge.worker

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetaChatClientTest {
    private val tools = listOf(
        ToolSchema(function = ToolSchema.Definition("finish", "ends the job", buildJsonObject { put("type", JsonPrimitive("object")) })),
    )
    private val messages = listOf(ChatMessage.system("rules"), ChatMessage.user("make a screen"))

    @Test
    fun `the request has the Meta shape - model, tools, max_tokens, bearer header, snake_case`() = runTest {
        val meta = FakeMeta(mutableListOf(textReply("hi")))

        meta.client(effort = "medium").chat(messages, tools)

        val request = meta.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://meta.test/v1/chat/completions", request.url.toString())
        assertEquals("Bearer ${FakeMeta.KEY}", request.headers[HttpHeaders.Authorization])
        val body = meta.bodies.single()
        assertEquals("muse-test", body["model"]!!.jsonPrimitive.content)
        assertEquals(16_000, body["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("medium", body["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(body["stream"]!!.jsonPrimitive.content.toBoolean())
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("finish", tool["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("object", tool["function"]!!.jsonObject["parameters"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val roles = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user"), roles)
        assertFalse(body.containsKey("temperature") && body.containsKey("top_p"), "Meta wants temperature or top_p, never both")
    }

    @Test
    fun `null fields are omitted - no temperature, top_p or reasoning_effort by default`() = runTest {
        val meta = FakeMeta(mutableListOf(textReply("hi")))

        meta.client().chat(messages, tools)

        val body = meta.bodies.single()
        for (key in listOf("temperature", "top_p", "reasoning_effort", "tool_choice")) assertFalse(body.containsKey(key), key)
    }

    @Test
    fun `temperature together with top_p is refused at construction`() {
        assertFailsWith<IllegalArgumentException> { FakeMeta(mutableListOf()).client(temperature = 0.2, topP = 0.9) }
    }

    @Test
    fun `either temperature or top_p alone is sent`() = runTest {
        val meta = FakeMeta(mutableListOf(textReply("a"), textReply("b")))

        meta.client(temperature = 0.2).chat(messages, tools)
        meta.client(topP = 0.9).chat(messages, tools)

        val (first, second) = meta.bodies
        assertTrue(first.containsKey("temperature") && !first.containsKey("top_p"))
        assertTrue(second.containsKey("top_p") && !second.containsKey("temperature"))
    }

    @Test
    fun `tool calls and tool results are encoded as the API expects`() = runTest {
        val meta = FakeMeta(mutableListOf(textReply("ok")))
        val call = ToolCall("call_1", function = ToolCall.FunctionCall("finish", "{\"status\":\"done\"}"))
        val history = messages + ChatMessage.assistant(AssistantReply(null, listOf(call), "tool_calls")) + ChatMessage.tool("call_1", "OK")

        meta.client().chat(history, tools)

        val sent = meta.bodies.single()["messages"]!!.jsonArray.map { it.jsonObject }
        val assistant = sent[2]
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val sentCall = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("function", sentCall["type"]!!.jsonPrimitive.content)
        assertEquals("{\"status\":\"done\"}", sentCall["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        assertEquals("call_1", sent[3]["tool_call_id"]!!.jsonPrimitive.content)
        assertNull(sent[3]["tool_calls"])
    }

    @Test
    fun `tool calls in the reply are parsed, including a missing id and object arguments`() = runTest {
        val payload = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
            "reasoning_content":"thinking","tool_calls":[
              {"id":"abc","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"a\"}"}},
              {"type":"function","function":{"name":"list_files","arguments":{"x":1}}}]}}]}"""
        val meta = FakeMeta(mutableListOf(payload))

        val reply = meta.client().chat(messages, tools)

        assertNull(reply.content)
        assertEquals("tool_calls", reply.finishReason)
        assertEquals(listOf("read_file", "list_files"), reply.toolCalls.map { it.function.name })
        assertEquals("abc", reply.toolCalls[0].id)
        assertEquals("{\"path\":\"a\"}", reply.toolCalls[0].function.arguments)
        assertEquals("call_1", reply.toolCalls[1].id)
        assertEquals("""{"x":1}""", reply.toolCalls[1].function.arguments)
    }

    @Test
    fun `an HTTP error becomes a readable message with status and body excerpt but no key`() = runTest {
        val engine = MockEngine { respond("""{"error":"bad key ${FakeMeta.KEY}"}""", HttpStatusCode.Unauthorized) }
        val client = MetaChatClient("https://meta.test/v1", FakeMeta.KEY, "m", engine, retryDelaysMs = emptyList())

        val error = assertFailsWith<MetaApiException> { client.chat(messages, tools) }

        assertEquals(401, error.status)
        assertContains(error.message!!, "HTTP 401")
        assertContains(error.message!!, "bad key")
        assertFalse(error.message!!.contains(FakeMeta.KEY))
    }

    @Test
    fun `a 400 on the reasoning field is retried once without it`() = runTest {
        var calls = 0
        val bodies = mutableListOf<JsonObject>()
        val engine = MockEngine { request ->
            calls++
            bodies += kotlinx.serialization.json.Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            if (calls == 1) respond("unsupported field reasoning_effort", HttpStatusCode.BadRequest)
            else respond(textReply("fine"), HttpStatusCode.OK, io.ktor.http.headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = MetaChatClient("https://meta.test/v1", FakeMeta.KEY, "m", engine, reasoningEffort = "high", retryDelaysMs = emptyList())

        val reply = client.chat(messages, tools)

        assertEquals("fine", reply.content)
        assertTrue(bodies[0].containsKey("reasoning_effort"))
        assertFalse(bodies[1].containsKey("reasoning_effort"))
    }

    @Test
    fun `server errors are retried and then reported`() = runTest {
        var calls = 0
        val engine = MockEngine { calls++; respond("overloaded", HttpStatusCode.ServiceUnavailable) }
        val client = MetaChatClient("https://meta.test/v1", FakeMeta.KEY, "m", engine, retryDelaysMs = listOf(1, 1))

        val error = assertFailsWith<MetaApiException> { client.chat(messages, tools) }

        assertEquals(503, error.status)
        assertEquals(3, calls)
    }

    @Test
    fun `a reply that is not a chat completion is a readable error`() = runTest {
        val meta = FakeMeta(mutableListOf("""{"choices":[]}"""))

        val error = assertFailsWith<MetaApiException> { meta.client().chat(messages, tools) }

        assertContains(error.message!!, "no choices")
    }
}
