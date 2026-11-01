package com.aiopenpocket.agent.core.llm

// @trace web-search-tool-c73ed8

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The web-search client against a fake engine: what goes to Meta's `/v1/responses`, how the documented
 * answer is read, and what is written to the usage log. The response shape is WRITTEN FROM Meta's
 * documentation (dev.meta.ai/docs/search-grounding "Example response" and the Responses schemas, read on
 * Nov 1, 2026), not recorded from a live answer - no paid call was made. Robolectric for `Log`, as in the
 * neighbouring client tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebSearchClientTest {

  private class RecordingUsage : LlmUsageRecorder {
    val calls = mutableListOf<LlmCallUsage>()
    override suspend fun record(usage: LlmCallUsage) {
      calls += usage
    }
  }

  /** The documented example, with its repeated TechTarget citation, plus the `usage` block of the schema. */
  private val documented = """
    {
      "id": "resp_123", "object": "response", "created_at": 1778250764, "status": "completed", "model": "muse-spark-1.3",
      "output": [
        {"id": "ws_789", "type": "web_search_call", "status": "completed"},
        {
          "id": "msg_b4d6e9c2ff37410a", "type": "message", "status": "completed", "role": "assistant",
          "content": [
            {
              "type": "output_text",
              "text": "**Muse Spark** was announced by Meta on **Wednesday, April 8, 2026**.",
              "annotations": [
                {"type": "url_citation", "url": "https://www.techtarget.com/searchcio/feature/weekly-roundup",
                 "title": "Weekly news roundup | TechTarget", "start_index": 38, "end_index": 76},
                {"type": "url_citation", "url": "https://www.thehindubusinessline.com/info-tech/muse-spark/article70840923.ece",
                 "title": "Meta unveils Muse Spark", "start_index": 175, "end_index": 225},
                {"type": "url_citation", "url": "https://www.techtarget.com/searchcio/feature/weekly-roundup",
                 "title": "Weekly news roundup | TechTarget", "start_index": 380, "end_index": 460}
              ]
            }
          ]
        }
      ],
      "usage": {"input_tokens": 2000, "input_tokens_details": {"cached_tokens": 0},
                "output_tokens": 500, "output_tokens_details": {"reasoning_tokens": 100}, "total_tokens": 2500}
    }
  """.trimIndent()

  private fun answering(body: String, status: HttpStatusCode = HttpStatusCode.OK) = MockEngine {
    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
  }

  private fun client(engine: MockEngine, usage: LlmUsageRecorder = NoOpLlmUsageRecorder, key: String = "sk-test") =
    WebSearchClient(engine, { key }, usage, model = { "muse-spark-1.3" })

  @Test
  fun `sends one Responses request with the web_search tool, store false, low effort and the model`() = runTest {
    var method: HttpMethod? = null
    var url = ""
    var auth: String? = null
    var body = ""
    val engine = MockEngine { request ->
      method = request.method
      url = request.url.toString()
      auth = request.headers[HttpHeaders.Authorization]
      body = (request.body as TextContent).text
      respond(documented, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    client(engine).search("when was Muse Spark announced", SearchDepth.DEEP)

    assertEquals(HttpMethod.Post, method)
    assertEquals("https://api.meta.ai/v1/responses", url)
    assertEquals("Bearer sk-test", auth)
    val sent = Json.parseToJsonElement(body).jsonObject
    // Meta answers 400 to a top-level field it does not know, so the key set is pinned, not just the values.
    assertEquals(setOf("model", "input", "instructions", "tools", "max_tool_calls", "store", "reasoning", "include"), sent.keys)
    assertEquals("web_search_call.results", sent.getValue("include").jsonArray.single().jsonPrimitive.content)
    assertEquals("muse-spark-1.3", sent.getValue("model").jsonPrimitive.content)
    assertEquals("when was Muse Spark announced", sent.getValue("input").jsonPrimitive.content)
    assertFalse(sent.getValue("store").jsonPrimitive.boolean)
    assertEquals("low", sent.getValue("reasoning").jsonObject.getValue("effort").jsonPrimitive.content)
    val tools: JsonArray = sent.getValue("tools").jsonArray
    assertEquals(1, tools.size)
    val tool: JsonObject = tools[0].jsonObject
    assertEquals(setOf("type", "search_context_size"), tool.keys)
    assertEquals("web_search", tool.getValue("type").jsonPrimitive.content)
    assertEquals("high", tool.getValue("search_context_size").jsonPrimitive.content)
    assertEquals(4, sent.getValue("max_tool_calls").jsonPrimitive.int)
  }

  @Test
  fun `depth picks the context size and the ceiling on searches`() = runTest {
    val bodies = mutableListOf<String>()
    val engine = MockEngine { request ->
      bodies += (request.body as TextContent).text
      respond(documented, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    val client = client(engine)

    client.search("q", SearchDepth.QUICK)
    client.search("q", SearchDepth.NORMAL)

    fun contextOf(body: String) = Json.parseToJsonElement(body).jsonObject.getValue("tools").jsonArray[0].jsonObject
      .getValue("search_context_size").jsonPrimitive.content
    assertEquals(listOf("low", "medium"), bodies.map(::contextOf))
    assertEquals(listOf(1, 2), bodies.map { Json.parseToJsonElement(it).jsonObject.getValue("max_tool_calls").jsonPrimitive.int })
    // Unknown or missing depth from the model is "normal", not an error.
    assertEquals(SearchDepth.NORMAL, SearchDepth.parse(null))
    assertEquals(SearchDepth.NORMAL, SearchDepth.parse("whatever"))
    assertEquals(SearchDepth.DEEP, SearchDepth.parse(" Deep "))
  }

  @Test
  fun `reads the answer text and a source list deduplicated by url`() = runTest {
    val result = client(answering(documented)).search("q")

    val found = (result as WebSearchResult.Found).answer
    assertEquals("**Muse Spark** was announced by Meta on **Wednesday, April 8, 2026**.", found.text)
    assertEquals(1, found.searches)
    // Three citations, two distinct pages: the repeated TechTarget one appears once, in first-seen order.
    assertEquals(
      listOf(
        WebSource("Weekly news roundup | TechTarget", "https://www.techtarget.com/searchcio/feature/weekly-roundup"),
        WebSource("Meta unveils Muse Spark", "https://www.thehindubusinessline.com/info-tech/muse-spark/article70840923.ece"),
      ),
      found.sources,
    )
  }

  @Test
  fun `a citation without a title falls back to the host and a non-web url is dropped`() = runTest {
    val body = """
      {"status":"completed","output":[{"type":"web_search_call"},{"type":"message","content":[{"type":"output_text","text":"ok",
      "annotations":[{"type":"url_citation","url":"https://example.org/a/b?x=1"},
                     {"type":"url_citation","url":"javascript:alert(1)","title":"bad"},
                     {"type":"file_citation","file_id":"f1"}]}]}]}
    """.trimIndent()

    val found = (client(answering(body)).search("q") as WebSearchResult.Found).answer

    assertEquals(listOf(WebSource("example.org", "https://example.org/a/b?x=1")), found.sources)
  }

  @Test
  fun `an answer with no citations is still returned, with an empty source list`() = runTest {
    val body = """
      {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"Paris.","annotations":[]}]}]}
    """.trimIndent()

    val found = (client(answering(body)).search("capital of France") as WebSearchResult.Found).answer

    assertEquals("Paris.", found.text)
    assertTrue(found.sources.isEmpty())
    assertEquals(0, found.searches)
  }

  /**
   * The shape a live muse-spark-1.3 answer had (1 Nov 2026): a `phase: "commentary"` message before the search, the
   * answer after it, and no url_citation anywhere — the pages are only in the search call's `results`.
   */
  private val liveShaped = """
    {"status":"completed","output":[
      {"type":"reasoning","summary":[]},
      {"type":"message","phase":"commentary","content":[{"type":"output_text","text":"Checking current sources.","annotations":[]}]},
      {"type":"web_search_call","status":"completed","action":{"type":"search","query":"latest kotlin"},
       "results":[
         {"type":"text_result","title":"Kotlin releases","url":"https://kotlinlang.org/docs/releases.html","snippet":"..."},
         {"type":"text_result","title":"","url":"https://github.com/JetBrains/kotlin/releases","snippet":"..."},
         {"type":"text_result","title":"Kotlin releases again","url":"https://kotlinlang.org/docs/releases.html","snippet":"..."}
       ]},
      {"type":"reasoning","summary":[]},
      {"type":"message","content":[{"type":"output_text","text":"Kotlin 2.4.21 is the latest stable release.","annotations":[]}]}
    ],"usage":{"input_tokens":2965,"output_tokens":418}}
  """.trimIndent()

  @Test
  fun `the commentary before the search is not part of the answer`() = runTest {
    val found = (client(answering(liveShaped)).search("latest kotlin") as WebSearchResult.Found).answer

    assertEquals("Kotlin 2.4.21 is the latest stable release.", found.text)
  }

  @Test
  fun `with no citations the retrieved pages become the sources, deduplicated, title falling back to the host`() = runTest {
    val found = (client(answering(liveShaped)).search("latest kotlin") as WebSearchResult.Found).answer

    assertEquals(
      listOf(
        WebSource("Kotlin releases", "https://kotlinlang.org/docs/releases.html"),
        WebSource("github.com", "https://github.com/JetBrains/kotlin/releases"),
      ),
      found.sources,
    )
    assertEquals(1, found.searches)
  }

  @Test
  fun `an HTTP error becomes a failure text with the status, not an exception`() = runTest {
    val error = """{"error":{"message":"web_search is not available for this model","type":"invalid_request_error","param":"tools"}}"""

    val rejected = client(answering(error, HttpStatusCode.BadRequest)).search("q")
    val busy = client(answering("{}", HttpStatusCode.TooManyRequests)).search("q")
    val down = client(answering("oops", HttpStatusCode.BadGateway)).search("q")

    rejected as WebSearchResult.Failed
    assertTrue(rejected.message, rejected.message.contains("400"))
    // Meta's own reason reaches the model, so it can tell "wrong request" from "wrong key".
    assertTrue(rejected.message, rejected.message.contains("web_search is not available for this model"))
    assertTrue((busy as WebSearchResult.Failed).message.contains("429"))
    assertTrue((down as WebSearchResult.Failed).message.contains("502"))
  }

  @Test
  fun `a timeout becomes a failure text`() = runTest {
    val engine = MockEngine { throw HttpRequestTimeoutException("https://api.meta.ai/v1/responses", 55_000L) }

    val result = client(engine).search("q")

    assertTrue("expected Failed, got $result", result is WebSearchResult.Failed)
    assertTrue((result as WebSearchResult.Failed).message.contains("55"))
  }

  @Test
  fun `a transport failure becomes a failure text`() = runTest {
    val engine = MockEngine { throw java.io.IOException("connection reset") }

    assertTrue(client(engine).search("q") is WebSearchResult.Failed)
  }

  /** Without a key the request is guaranteed a 401 - it must not be sent, and no money is logged. */
  @Test
  fun `no key means no request and no spend`() = runTest {
    var requests = 0
    val engine = MockEngine {
      requests++
      respond(documented, HttpStatusCode.OK)
    }
    val usage = RecordingUsage()

    val result = client(engine, usage, key = "  ").search("q")

    assertEquals(0, requests)
    assertEquals(missingKeyMessage(), (result as WebSearchResult.Failed).message)
    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `the usage record has the tokens and the fee for each search`() = runTest {
    val usage = RecordingUsage()

    client(answering(documented), usage).search("q")

    val call = usage.calls.single()
    assertEquals(LlmProvider.META, call.provider)
    assertEquals("muse-spark-1.3", call.model)
    assertEquals(2000, call.promptTokens)
    assertEquals(500, call.completionTokens)
    // Spark list price for 2000 in + 500 out, plus one search at $0.0025.
    val tokens = (2000 * 1.25 + 500 * 4.25) / 1_000_000.0
    assertEquals(tokens + 0.0025, call.costUsd!!, 1e-9)
    assertFalse(call.estimated)
    assertTrue(call.hadTools)
  }

  @Test
  fun `every web_search_call item is billed, and a missing usage block is marked estimated`() = runTest {
    val body = """
      {"status":"completed","output":[{"type":"web_search_call"},{"type":"web_search_call"},{"type":"web_search_call"},
      {"type":"message","content":[{"type":"output_text","text":"x"}]}]}
    """.trimIndent()
    val usage = RecordingUsage()

    client(answering(body), usage).search("q")

    val call = usage.calls.single()
    assertEquals(0.0075, call.costUsd!!, 1e-12)
    assertTrue("no usage block - the token part is a guess", call.estimated)
  }

  @Test
  fun `a failed request records nothing`() = runTest {
    val usage = RecordingUsage()

    client(answering("{}", HttpStatusCode.InternalServerError), usage).search("q")

    assertTrue(usage.calls.isEmpty())
  }

  @Test
  fun `a search that ran but returned no text is a failure, and still billed`() = runTest {
    val body = """{"status":"completed","output":[{"type":"web_search_call"}],"usage":{"input_tokens":10,"output_tokens":5}}"""
    val usage = RecordingUsage()

    val result = client(answering(body), usage).search("q")

    assertTrue(result is WebSearchResult.Failed)
    assertNotNull(usage.calls.singleOrNull())
  }

  @Test
  fun `the model is the highest ranked Spark of the catalog, never Wonder or a contributor build`() {
    val catalog = ModelCatalog(
      models = listOf(
        ModelEntry(name = "muse-wonder-1.0", priority = 1),
        ModelEntry(name = "muse-spark-1.3-contributor", priority = 2),
        ModelEntry(name = "muse-spark-1.4", priority = 3),
        ModelEntry(name = "muse-spark-1.3", priority = 4),
      ),
    )

    assertEquals("muse-spark-1.4", catalog.preferredSparkId())
    assertEquals("muse-spark-1.3", ModelCatalog(models = emptyList()).preferredSparkId())
  }
}
