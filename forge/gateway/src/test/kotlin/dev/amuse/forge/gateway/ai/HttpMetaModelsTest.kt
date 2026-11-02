package dev.amuse.forge.gateway.ai

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpMetaModelsTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val sent = mutableListOf<Pair<String, String>>()

    private fun models(status: HttpStatusCode, body: String) = HttpMetaModels(
        baseUrl = "https://api.meta.ai/v1",
        apiKey = "sk-secret-key",
        engine = MockEngine { request ->
            sent += request.url.encodedPath to (request.body as TextContent).text
            respond(body, status, json)
        },
    )

    @Test
    fun `chat sends the Meta shape and returns the answer text`() = runBlocking<Unit> {
        val reply = """{"choices":[{"message":{"role":"assistant","content":"hello","reasoning_content":"thinking"}}]}"""
        val text = models(HttpStatusCode.OK, reply).complete("be brief", "hi", effort = "low")
        assertEquals("hello", text)

        val (path, body) = sent.single()
        assertEquals("/v1/chat/completions", path)
        val request = Json.parseToJsonElement(body).jsonObject
        assertEquals("muse-spark-1.3", request["model"]!!.jsonPrimitive.content)
        assertEquals("low", request["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals(listOf("system", "user"), request["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertFalse("temperature" in request && "top_p" in request)
    }

    @Test
    fun `an image comes back as bytes with the format it was asked in`() = runBlocking<Unit> {
        val b64 = Base64.getEncoder().encodeToString("PNG!".toByteArray())
        val image = models(HttpStatusCode.OK, """{"data":[{"b64_json":"$b64"}],"output_format":"png"}""").image("a cat")
        assertEquals("PNG!", String(image.bytes))
        assertEquals("image/png", image.mediaType)

        val request = Json.parseToJsonElement(sent.single().second).jsonObject
        assertEquals(setOf("model", "prompt", "size", "response_format", "output_format"), request.keys)
        assertEquals("/v1/images/generations", sent.single().first)
    }

    @Test
    fun `errors carry the status and never the key`() = runBlocking<Unit> {
        val error = assertFailsWith<MetaCallException> {
            models(HttpStatusCode.Unauthorized, """{"error":"bad key sk-secret-key"}""").complete("s", "u")
        }
        assertEquals(401, error.status)
        assertFalse("sk-secret-key" in error.message.orEmpty())
        assertTrue("***" in error.message.orEmpty())
    }

    @Test
    fun `an answer with no text is an error, not an empty review`() = runBlocking<Unit> {
        assertFailsWith<MetaCallException> {
            models(HttpStatusCode.OK, """{"choices":[{"message":{"content":null,"reasoning_content":"hmm"}}]}""").complete("s", "u")
        }
    }

    @Test
    fun `the first json object is found inside prose and fences`() {
        assertEquals("""{"a":{"b":"}"}}""", extractJsonObject("Sure! ```json\n{\"a\":{\"b\":\"}\"}}\n``` done"))
        assertEquals(null, extractJsonObject("no json here"))
    }
}
