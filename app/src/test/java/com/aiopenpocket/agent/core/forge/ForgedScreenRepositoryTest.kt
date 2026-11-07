package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.i18n.AppLanguage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ForgedScreenRepository] end to end: ask, follow the job, answer its question, change a ready
 * screen, retry, crash sentinel, remove. A real [ForgedScreenStore] on the Robolectric `filesDir`, a
 * scripted gateway behind a Ktor `MockEngine`. This is where the polling machinery that used to be
 * tested through the detail ViewModel now lives.
 *
 * Mechanics worth knowing:
 *  - the gateway's `GET /v1/jobs/{id}` answers are fed one at a time through [FakeGateway.statusReplies]
 *    and the handler SUSPENDS on an empty channel. That is what makes every intermediate state
 *    observable (a StateFlow conflates, a free-running mock would show only the last one) and makes
 *    "polling stopped" measurable: a poller that goes on enters the handler again and bumps
 *    [FakeGateway.statusCalls]. A call number N+1 entering the handler also proves reply N was fully
 *    processed (state set and persisted), because the poller only asks again after that.
 *  - the repository runs on a scope the test owns ([testDispatcher]); the store and the client work on
 *    `Dispatchers.IO`, which the test scheduler does not track, so state is awaited in REAL time
 *    ([awaitUi]) while the 10 ms delay between polls is virtual and skipped by `runTest`.
 *  - assertions are on tokens only the mock or the test invents (job ids, entry points, progress lines),
 *    never on a state the initial load already satisfies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForgedScreenRepositoryTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val application = RuntimeEnvironment.getApplication()
  private val repoScope = CoroutineScope(testDispatcher + SupervisorJob())

  private lateinit var store: ForgedScreenStore
  private lateinit var gateway: FakeGateway
  private lateinit var repo: ForgedScreenRepository
  /** The setUp repository's flow for [agentId]; lazy, so a test that stores state first is not raced by a load in setUp. */
  private val ui: StateFlow<ForgedAgentUi> by lazy { repo.observe(agentId) }

  private val agentId = "forge-agent"

  /** What the person wrote in the brief sheet: every build in these tests starts from it. */
  private val brief = ScreenBrief(
    text = "Glasses drunk today and a button to add one",
    blocks = setOf(ScreenBlock.ADD_FORM, ScreenBlock.TOTAL),
    layout = ScreenLayout.DASHBOARD,
  )

  private val definition: AgentDefinition = MiniAgentCatalog.get("budget")!!.toDefinition().copy(
    id = "forge-agent",
    source = AgentSource.USER,
    name = "Zorblax Counter",
    description = "Counts zorblax glasses every day",
    systemPrompt = "You keep the zorblax tally.",
  )

  /** One-shot reply to a status poll: a JSON body, or a transport failure. */
  private sealed interface Reply {
    data class Json(val body: String) : Reply
    data class Fail(val error: IOException) : Reply
  }

  /** The forge gateway, scripted by the test. Handlers run on engine threads, hence the thread-safe holders. */
  private class FakeGateway(val jobId: String = "job-fake-42") {
    /** Every request as "METHOD /path". */
    val log = CopyOnWriteArrayList<String>()
    val submitBodies = CopyOnWriteArrayList<String>()
    val answerBodies = CopyOnWriteArrayList<String>()
    val statusPaths = CopyOnWriteArrayList<String>()
    val statusCalls = AtomicInteger()

    /** What `POST /v1/jobs` answers. */
    @Volatile var submitStatus: HttpStatusCode = HttpStatusCode.Accepted

    /** What `POST /v1/jobs/{id}/answer` answers. */
    @Volatile var answerStatus: HttpStatusCode = HttpStatusCode.OK

    /** Ids handed out by consecutive submits; the last one repeats. */
    @Volatile var nextJobIds: List<String> = listOf(jobId)
    private val submits = AtomicInteger()

    /** What `GET /v1/artifacts/...` serves. */
    @Volatile var bundle: ByteArray = ByteArray(96) { (it * 5 + 3).toByte() }

    val statusReplies = Channel<Reply>(Channel.UNLIMITED)

    val engine = MockEngine { request ->
      log += "${request.method.value} ${request.url.encodedPath}"
      val path = request.url.encodedPath
      val json = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        request.method == HttpMethod.Post && path == "/v1/jobs" -> {
          submitBodies += (request.body as TextContent).text
          if (submitStatus == HttpStatusCode.Accepted) {
            val ids = nextJobIds
            val id = ids[minOf(submits.getAndIncrement(), ids.lastIndex)]
            respond("""{"jobId":"$id"}""", HttpStatusCode.Accepted, json)
          } else {
            respond("denied", submitStatus)
          }
        }

        request.method == HttpMethod.Post && path.endsWith("/answer") -> {
          answerBodies += (request.body as TextContent).text
          if (answerStatus == HttpStatusCode.OK) {
            respond("""{"jobId":"$jobId","status":"queued"}""", HttpStatusCode.OK, json)
          } else {
            respond("nope", answerStatus)
          }
        }

        request.method == HttpMethod.Get && path.startsWith("/v1/jobs/") -> {
          statusPaths += path
          statusCalls.incrementAndGet()
          when (val reply = statusReplies.receive()) {
            is Reply.Json -> respond(reply.body, HttpStatusCode.OK, json)
            is Reply.Fail -> throw reply.error
          }
        }

        request.method == HttpMethod.Get && path.startsWith("/v1/artifacts/") ->
          respond(bundle, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))

        else -> respond("", HttpStatusCode.NotFound)
      }
    }

    fun reply(body: String) {
      statusReplies.trySend(Reply.Json(body))
    }

    fun working(message: String? = null, id: String = jobId) =
      reply("""{"jobId":"$id","status":"working"${message?.let { ""","message":"$it"""" }.orEmpty()}}""")

    fun needsInfo(question: String, id: String = jobId) =
      reply("""{"jobId":"$id","status":"needs_info","question":"$question"}""")

    fun failedJob(message: String, id: String = jobId) =
      reply("""{"jobId":"$id","status":"failed","message":"$message"}""")

    fun done(entryPoint: String, version: Int, id: String = jobId, sha: String = ScreenForgeClient.sha256Hex(bundle)) =
      reply(
        """{"jobId":"$id","status":"done","artifact":{"url":"/v1/artifacts/$id.ktx","sha256":"$sha",""" +
          """"entryPoint":"$entryPoint","version":$version,"sizeBytes":${bundle.size}}}""",
      )

    fun transportFailure() {
      statusReplies.trySend(Reply.Fail(IOException("socket reset by mock")))
    }
  }

  @Before
  fun setUp() {
    AppLanguage.code = AppLanguage.EN
    store = ForgedScreenStore(application)
    gateway = FakeGateway()
    repo = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key"))
  }

  @After
  fun tearDown() {
    // Stop the pollers first (one may be parked in the handler's receive()), then free the channel.
    repoScope.cancel()
    gateway.statusReplies.close()
    AppLanguage.code = AppLanguage.RU
  }

  private fun repository(config: ForgeGatewayConfig) = ForgedScreenRepository(
    store = store,
    client = ScreenForgeClient(gateway.engine) { config },
    scope = repoScope,
    pollIntervalMs = 10L,
  )

  /** Waits in REAL time: the store and the client run on IO, which the test scheduler does not track. */
  private suspend fun awaitUi(
    flow: StateFlow<ForgedAgentUi> = ui,
    timeoutMs: Long = 10_000,
    predicate: (ForgedAgentUi) -> Boolean,
  ): ForgedAgentUi = withContext(Dispatchers.Default) { withTimeout(timeoutMs) { flow.first(predicate) } }

  private suspend fun awaitLoaded(flow: StateFlow<ForgedAgentUi> = ui) = awaitUi(flow) { it.loaded }

  private suspend fun awaitCalls(count: Int) {
    withContext(Dispatchers.Default) {
      withTimeout(10_000) { while (gateway.statusCalls.get() < count) delay(5) }
    }
  }

  /** Real time passes and nothing else must happen: used to prove a poller has really stopped. */
  private suspend fun settle(ms: Long = 250) {
    withContext(Dispatchers.Default) { delay(ms) }
  }

  /**
   * Messages built on the poller's IO thread can come out in either language: the `tr` switch is Compose
   * snapshot state, and a background thread was seen reading the Russian default even with English set
   * on the test thread. The wording is not what these tests guard, so a message is matched in both.
   */
  private fun assertSaysEither(message: String, ru: String, en: String) {
    assertTrue(message, message.contains(ru) || message.contains(en))
  }

  private fun bodyOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

  private fun stateFile() = File(File(File(application.filesDir, ForgedScreenStore.DIR_NAME), agentId), ForgedScreenStore.STATE_FILE)

  private fun agentDir() = File(File(application.filesDir, ForgedScreenStore.DIR_NAME), agentId)

  private suspend fun storeReady(jobId: String = "job-old-5", request: String = "first version"): ForgedScreenState.Ready {
    val fileName = store.writeBundle(agentId, jobId, byteArrayOf(1, 2, 3))
    return ForgedScreenState.Ready(jobId, request, "OldEntry", 1, fileName).also { store.save(agentId, it) }
  }

  // --- Loading ------------------------------------------------------------------------------------

  @Test
  fun `the first load of an agent with nothing stored is loaded, None and silent`() = runTest(testDispatcher) {
    val first = awaitLoaded()

    assertEquals(ForgedScreenState.None, first.state)
    assertNull(first.bundle)
    assertFalse(first.customUiEnabled)
    assertFalse(first.isReady)
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  @Test
  fun `before the first load the screen is not loaded, so nothing forge-related shows`() = runTest(testDispatcher) {
    // A repository whose scope never runs anything: observe() hands out the initial, unloaded value.
    val idle = ForgedScreenRepository(store, ScreenForgeClient(gateway.engine) { ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key") }, scope = CoroutineScope(UnconfinedTestDispatcher()).also { it.cancel() })
    assertFalse(idle.observe("never-loaded").value.loaded)
  }

  @Test
  fun `a stored Ready state is read back with its bundle and the switch, without asking the gateway`() = runTest(testDispatcher) {
    val ready = storeReady("job-disk-1", "stored request")
    store.setCustomUiEnabled(agentId, true)
    val fresh = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key"))

    val loaded = awaitUi(fresh.observe(agentId)) { it.loaded }

    assertEquals(ready, loaded.state)
    assertArrayEquals(byteArrayOf(1, 2, 3), loaded.bundle)
    assertTrue(loaded.customUiEnabled)
    assertTrue(loaded.isReady)
    assertTrue(loaded.showsCustomUi)
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  @Test
  fun `a Ready screen whose bundle is gone from disk is not ready and does not show the custom interface`() = runTest(testDispatcher) {
    store.save(agentId, ForgedScreenState.Ready("job-lost-1", "req", "LostEntry", 1, "job-lost-1.ktx"))
    store.setCustomUiEnabled(agentId, true)
    val fresh = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key"))

    val loaded = awaitUi(fresh.observe(agentId)) { it.loaded }

    assertEquals("job-lost-1", (loaded.state as ForgedScreenState.Ready).jobId)
    assertNull(loaded.bundle)
    assertFalse(loaded.isReady)
    assertFalse(loaded.showsCustomUi)
  }

  @Test
  fun `a stored Working state resumes polling that job and nothing is submitted`() = runTest(testDispatcher) {
    store.save(agentId, ForgedScreenState.Working("job-stored-9", "stored request", null))
    val fresh = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key"))
    val flow = fresh.observe(agentId)

    awaitCalls(1)

    // The poller asks for the STORED job.
    assertEquals(listOf("/v1/jobs/job-stored-9"), gateway.statusPaths.toList())
    assertTrue(gateway.submitBodies.isEmpty())
    gateway.working(message = "resumed-after-restart", id = "job-stored-9")
    val resumed = awaitUi(flow) { (it.state as? ForgedScreenState.Working)?.message == "resumed-after-restart" }
    assertEquals("job-stored-9", resumed.state.jobId)
  }

  @Test
  fun `a stored Requested state resumes polling too`() = runTest(testDispatcher) {
    store.save(agentId, ForgedScreenState.Requested("job-stored-3", "stored request"))
    repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).observe(agentId)

    awaitCalls(1)

    assertEquals(listOf("/v1/jobs/job-stored-3"), gateway.statusPaths.toList())
  }

  @Test
  fun `stored NeedsInfo or Failed do not poll`() = runTest(testDispatcher) {
    store.save(agentId, ForgedScreenState.NeedsInfo("job-ask-1", "req", "q?"))
    val asking = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).observe(agentId)
    assertEquals("q?", (awaitUi(asking) { it.state is ForgedScreenState.NeedsInfo }.state as ForgedScreenState.NeedsInfo).question)
    settle()
    assertEquals(0, gateway.statusCalls.get())

    store.save(agentId, ForgedScreenState.Failed("job-fail-1", "req", "boom"))
    val failed = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).observe(agentId)
    awaitUi(failed) { (it.state as? ForgedScreenState.Failed)?.message == "boom" }
    settle()
    assertEquals(0, gateway.statusCalls.get())
  }

  @Test
  fun `every observer of an agent sees the same updates and another agent's screen stays apart`() = runTest(testDispatcher) {
    val first = repo.observe(agentId)
    val second = repo.observe(agentId)
    val other = repo.observe("other-agent")
    awaitUi(first) { it.loaded }
    awaitUi(other) { it.loaded }

    repo.showSample(agentId, byteArrayOf(3), "shared request")

    assertEquals("shared request", (first.value.state as ForgedScreenState.Ready).request)
    assertEquals("shared request", (second.value.state as ForgedScreenState.Ready).request)
    assertEquals(ForgedScreenState.None, other.value.state)
  }

  // --- The happy path -----------------------------------------------------------------------------

  @Test
  fun `asking for an agent screen is sent with the agent's name, persisted as Requested, followed through Working and ends Ready with the bundle on disk`() = runTest(testDispatcher) {
    awaitLoaded()

    repo.requestFromBrief(definition, brief)

    // 1. Requested: the id is the mock's, the request text is built from the agent (its name, its description).
    val requested = awaitUi { it.state is ForgedScreenState.Requested }
    val request = (requested.state as ForgedScreenState.Requested).request
    assertEquals("job-fake-42", requested.state.jobId)
    assertTrue(request, request.contains("Zorblax Counter"))
    assertTrue(request, request.contains("Counts zorblax glasses every day"))
    assertEquals(AgentUiRequest.fromBrief(definition, brief), request)
    assertNull(requested.state.previousJobId)
    val sent = bodyOf(gateway.submitBodies.single())
    assertEquals(agentId, sent.getValue("agentId").jsonPrimitive.content)
    assertEquals("Zorblax Counter", sent.getValue("agentName").jsonPrimitive.content)
    assertEquals(request, sent.getValue("request").jsonPrimitive.content)
    assertFalse("previousJobId" in sent)
    // Persisted BEFORE the first poll answers: a restart now would resume this job.
    assertEquals(requested.state, store.load(agentId))

    // 2. Working with the worker's progress line.
    gateway.working(message = "compiling-the-glasses")
    val working = awaitUi { (it.state as? ForgedScreenState.Working)?.message == "compiling-the-glasses" }
    assertEquals(ForgedScreenState.Working("job-fake-42", request, "compiling-the-glasses"), working.state)
    assertEquals(working.state, store.load(agentId))

    // 3. Done: the bundle is downloaded, verified against the announced sha256, written, and the state
    //    names the mock's entry point and version.
    gateway.done(entryPoint = "GlassesScreenEntry", version = 4)
    val ready = awaitUi { it.state is ForgedScreenState.Ready }
    assertEquals(ForgedScreenState.Ready("job-fake-42", request, "GlassesScreenEntry", 4, "job-fake-42.ktx"), ready.state)
    assertArrayEquals(gateway.bundle, ready.bundle)
    assertTrue(ready.isReady)
    assertArrayEquals(gateway.bundle, store.readBundle(agentId, "job-fake-42.ktx"))
    assertEquals(ready.state, store.load(agentId))
    assertTrue("GET /v1/artifacts/job-fake-42.ktx" in gateway.log)
    assertEquals(listOf("/v1/jobs/job-fake-42", "/v1/jobs/job-fake-42"), gateway.statusPaths.toList())

    // 4. Polling ended with the job: nobody asks again.
    settle()
    assertEquals(2, gateway.statusCalls.get())
  }

  @Test
  fun `a progress line that did not change is not written to disk again`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }

    gateway.working(message = "line-A") // answer to poll #1
    awaitCalls(2) // poll #2 entered, so line-A is processed and persisted
    assertEquals("line-A", (store.load(agentId) as ForgedScreenState.Working).message)

    // Take the file away: only a new write can bring it back.
    assertTrue(stateFile().delete())
    gateway.working(message = "line-A") // the SAME line again, answer to poll #2
    awaitCalls(3)
    settle(100)
    assertFalse("an unchanged Working state must not be persisted on every poll", stateFile().exists())

    gateway.working(message = "line-B") // a changed line, answer to poll #3
    awaitCalls(4)
    assertEquals("line-B", (store.load(agentId) as ForgedScreenState.Working).message)
  }

  @Test
  fun `queued and accepted answers keep the Requested state and write nothing`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    val requested = awaitUi { it.state is ForgedScreenState.Requested }
    assertTrue(stateFile().delete())

    gateway.reply("""{"jobId":"job-fake-42","status":"queued"}""")
    gateway.reply("""{"jobId":"job-fake-42","status":"accepted"}""")
    awaitCalls(3)
    settle(100)

    assertEquals(requested.state, ui.value.state)
    assertFalse(stateFile().exists())
  }

  // --- needs_info and the answer ------------------------------------------------------------------

  @Test
  fun `needs_info shows the question and stops polling, the answer is posted and polling resumes`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }

    gateway.needsInfo("Which-currency-mock?")
    val asked = awaitUi { it.state is ForgedScreenState.NeedsInfo }
    val request = AgentUiRequest.fromBrief(definition, brief)
    assertEquals(ForgedScreenState.NeedsInfo("job-fake-42", request, "Which-currency-mock?"), asked.state)
    assertEquals(asked.state, store.load(agentId))

    // Polling stopped: with a live poller a second status request would already be parked in the handler.
    settle()
    assertEquals(1, gateway.statusCalls.get())

    var failure: String? = null
    repo.answer(agentId, "  euros  ") { failure = it }

    val resumed = awaitUi { it.state is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", request), resumed.state)
    assertNull(failure)
    assertEquals("euros", bodyOf(gateway.answerBodies.single()).getValue("text").jsonPrimitive.content)
    assertTrue("POST /v1/jobs/job-fake-42/answer" in gateway.log)
    // Polling is back: the second status request arrives, and its answer moves the state on.
    awaitCalls(2)
    gateway.done(entryPoint = "DonutEntry", version = 1)
    val ready = awaitUi { it.state is ForgedScreenState.Ready }
    assertEquals("DonutEntry", (ready.state as ForgedScreenState.Ready).entryPoint)
  }

  @Test
  fun `answering a question about a change queues the job again with the same previousJobId`() = runTest(testDispatcher) {
    storeReady("job-old-5")
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.isReady }
    repo.requestChange(agentId, definition, "make it blue")
    awaitUi(flow) { it.state is ForgedScreenState.Requested }
    gateway.needsInfo("Which-blue-mock?")
    val asked = awaitUi(flow) { it.state is ForgedScreenState.NeedsInfo }
    // The question card keeps the screen the change applies to, otherwise the answer would start a brand-new screen.
    assertEquals("job-old-5", asked.state.previousJobId)

    repo.answer(agentId, "navy")

    val resumed = awaitUi(flow) { it.state is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", "make it blue", "job-old-5"), resumed.state)
    assertEquals(resumed.state, store.load(agentId))
  }

  @Test
  fun `a refused answer reports the reason, leaves the question open and does not poll`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }
    gateway.needsInfo("Which-currency-mock?")
    val asked = awaitUi { it.state is ForgedScreenState.NeedsInfo }
    gateway.answerStatus = HttpStatusCode.Forbidden
    var failure: String? = null

    repo.answer(agentId, "euros") { failure = it }
    withContext(Dispatchers.Default) { withTimeout(10_000) { while (failure == null) delay(5) } }

    assertSaysEither(failure!!, "не приняла токен", "turned the token down")
    assertEquals(asked.state, ui.value.state)
    settle()
    assertEquals(1, gateway.statusCalls.get())
  }

  @Test
  fun `an answer is ignored unless the agent is asking, and a blank one is ignored always`() = runTest(testDispatcher) {
    awaitLoaded()

    repo.answer(agentId, "nobody asked")
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())

    store.save(agentId, ForgedScreenState.NeedsInfo("job-ask-1", "req", "q?"))
    val asking = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key"))
    awaitUi(asking.observe(agentId)) { it.state is ForgedScreenState.NeedsInfo }
    asking.answer(agentId, "   ")
    settle(100)
    assertTrue(gateway.answerBodies.isEmpty())
  }

  // --- Changing and retrying ----------------------------------------------------------------------

  @Test
  fun `a change request sends the Ready job as previousJobId and keeps it through Working and Failed`() = runTest(testDispatcher) {
    storeReady("job-old-5")
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.isReady }

    repo.requestChange(agentId, definition, "  make the glasses blue  ")

    val requested = awaitUi(flow) { it.state is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", "make the glasses blue", "job-old-5"), requested.state)
    val sent = bodyOf(gateway.submitBodies.single())
    assertEquals("job-old-5", sent.getValue("previousJobId").jsonPrimitive.content)
    assertEquals("make the glasses blue", sent.getValue("request").jsonPrimitive.content)
    assertEquals(requested.state, store.load(agentId))

    gateway.working(message = "recolouring")
    val working = awaitUi(flow) { it.state is ForgedScreenState.Working }
    assertEquals(ForgedScreenState.Working("job-fake-42", "make the glasses blue", "recolouring", "job-old-5"), working.state)
    assertEquals(working.state, store.load(agentId))

    gateway.failedJob("kotlinc-exploded-mock")
    val failed = awaitUi(flow) { it.state is ForgedScreenState.Failed }
    assertEquals(
      ForgedScreenState.Failed("job-fake-42", "make the glasses blue", "kotlinc-exploded-mock", "job-old-5"),
      failed.state,
    )
    assertEquals(failed.state, store.load(agentId))
    // The old screen is gone from the card (the job replaced it), and polling ended with the job.
    assertNull(failed.bundle)
    settle()
    assertEquals(2, gateway.statusCalls.get())
  }

  @Test
  fun `try again after a failed change re-sends it with the same previousJobId`() = runTest(testDispatcher) {
    // What the person sees after "make it blue" failed: a Failed card whose Retry must NOT turn the change
    // into a request for a brand-new screen.
    store.save(agentId, ForgedScreenState.Failed("job-fail-1", "make it blue", "boom", previousJobId = "job-old-5"))
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.state is ForgedScreenState.Failed }

    repo.retry(agentId, definition)

    val requested = awaitUi(flow) { it.state is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", "make it blue", "job-old-5"), requested.state)
    val sent = bodyOf(gateway.submitBodies.single())
    assertEquals("make it blue", sent.getValue("request").jsonPrimitive.content)
    assertEquals("job-old-5", sent.getValue("previousJobId").jsonPrimitive.content)
  }

  @Test
  fun `a change refused at submit fails without a job id, still remembers what it changes, and retry sends it again`() = runTest(testDispatcher) {
    storeReady("job-old-5")
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.isReady }
    gateway.submitStatus = HttpStatusCode.Unauthorized

    repo.requestChange(agentId, definition, "add a reset button")

    val failed = awaitUi(flow) { it.state is ForgedScreenState.Failed }
    val state = failed.state as ForgedScreenState.Failed
    assertNull(state.jobId)
    assertEquals("add a reset button", state.request)
    assertEquals("job-old-5", state.previousJobId)
    assertSaysEither(state.message, "Настройках", "Settings")
    assertEquals(state, store.load(agentId))
    settle(100)
    assertEquals(0, gateway.statusCalls.get())

    // The gateway accepts the token now; Retry carries the original screen again.
    gateway.submitStatus = HttpStatusCode.Accepted
    repo.retry(agentId, definition)

    val requested = awaitUi(flow) { it.state is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", "add a reset button", "job-old-5"), requested.state)
    assertEquals("job-old-5", bodyOf(gateway.submitBodies.last()).getValue("previousJobId").jsonPrimitive.content)
    assertEquals(2, gateway.submitBodies.size)
  }

  @Test
  fun `try again after a failed first build re-sends the request without a previousJobId`() = runTest(testDispatcher) {
    store.save(agentId, ForgedScreenState.Failed("job-fail-1", "the original ask", "boom"))
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.state is ForgedScreenState.Failed }

    repo.retry(agentId, definition)

    val requested = awaitUi(flow) { it.state is ForgedScreenState.Requested }
    assertEquals("the original ask", requested.state.lastRequest)
    val sent = bodyOf(gateway.submitBodies.single())
    assertEquals("the original ask", sent.getValue("request").jsonPrimitive.content)
    assertFalse("previousJobId" in sent)
  }

  @Test
  fun `a change on a screen that is not Ready, a blank change and a retry with nothing failed send nothing`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestChange(agentId, definition, "make it blue") // None
    repo.retry(agentId, definition) // None
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())

    storeReady("job-old-5")
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.isReady }
    repo.requestChange(agentId, definition, "   ") // blank
    repo.retry(agentId, definition) // Ready is not Failed
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  // --- Failures -----------------------------------------------------------------------------------

  @Test
  fun `five transport failures in a row end the job as Failed`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }
    // More than five are queued: a poller that gave up one call late would be caught by the exact count.
    repeat(8) { gateway.transportFailure() }

    val failed = awaitUi { it.state is ForgedScreenState.Failed }

    val state = failed.state as ForgedScreenState.Failed
    assertEquals("job-fake-42", state.jobId)
    assertEquals(AgentUiRequest.fromBrief(definition, brief), state.request)
    assertTrue(state.message, state.message.contains("socket reset by mock"))
    assertEquals(state, store.load(agentId))
    settle()
    assertEquals(5, gateway.statusCalls.get())
  }

  @Test
  fun `a good answer in between resets the failure count`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }
    repeat(4) { gateway.transportFailure() }
    gateway.working(message = "still-alive-after-4")
    repeat(4) { gateway.transportFailure() }
    gateway.done(entryPoint = "SurvivorEntry", version = 1)

    val ready = awaitUi { it.state is ForgedScreenState.Ready }

    assertEquals("SurvivorEntry", (ready.state as ForgedScreenState.Ready).entryPoint)
    assertEquals(10, gateway.statusCalls.get())
  }

  @Test
  fun `a job the gateway reports as failed ends polling with the gateway's message`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }

    gateway.failedJob("kotlinc-exploded-mock")

    val failed = awaitUi { it.state is ForgedScreenState.Failed }
    assertEquals(ForgedScreenState.Failed("job-fake-42", AgentUiRequest.fromBrief(definition, brief), "kotlinc-exploded-mock"), failed.state)
    assertEquals(failed.state, store.load(agentId))
    settle()
    assertEquals(1, gateway.statusCalls.get())
  }

  @Test
  fun `done without an artifact is a Failed, not a crash`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }

    gateway.reply("""{"jobId":"job-fake-42","status":"done"}""")

    val failed = awaitUi { it.state is ForgedScreenState.Failed }
    assertSaysEither((failed.state as ForgedScreenState.Failed).message, "без бандла", "without a bundle")
  }

  @Test
  fun `a bundle that fails the checksum is not stored and the job ends Failed`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }

    gateway.done(entryPoint = "SwappedEntry", version = 1, sha = "0".repeat(64))

    val failed = awaitUi { it.state is ForgedScreenState.Failed }
    assertSaysEither((failed.state as ForgedScreenState.Failed).message, "контрольная сумма", "checksum")
    assertNull(failed.bundle)
    assertNull(store.readBundle(agentId, "job-fake-42.ktx"))
  }

  @Test
  fun `a refused submit becomes Failed with no job id and a hint about Settings`() = runTest(testDispatcher) {
    awaitLoaded()
    gateway.submitStatus = HttpStatusCode.Unauthorized

    repo.requestFromBrief(definition, brief)

    val failed = awaitUi { it.state is ForgedScreenState.Failed }
    val state = failed.state as ForgedScreenState.Failed
    assertNull(state.jobId)
    assertEquals(AgentUiRequest.fromBrief(definition, brief), state.request)
    assertSaysEither(state.message, "Настройках", "Settings")
    assertEquals(state, store.load(agentId))
    settle(100)
    assertEquals(0, gateway.statusCalls.get())
  }

  @Test
  fun `without a gateway address and token the repository is not configured and a request fails with the hint`() = runTest(testDispatcher) {
    val unconfigured = repository(ForgeGatewayConfig("", ""))
    val flow = unconfigured.observe(agentId)
    awaitUi(flow) { it.loaded }

    assertFalse(unconfigured.isConfigured())
    assertTrue(repo.isConfigured())
    unconfigured.requestFromBrief(definition, brief)

    val failed = awaitUi(flow) { it.state is ForgedScreenState.Failed }
    assertSaysEither((failed.state as ForgedScreenState.Failed).message, "адрес кузницы и токен", "forge address and token")
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  // --- Crash sentinel -----------------------------------------------------------------------------

  @Test
  fun `a rendering flag left behind takes the Ready screen down instead of composing it again`() = runTest(testDispatcher) {
    val fileName = store.writeBundle(agentId, "job-crashed-9", ByteArray(8) { 1 })
    store.save(agentId, ForgedScreenState.Ready("job-crashed-9", "a tracker", "CrashScreen", 1, fileName))
    // What the section writes right before the bundle composes; the process then died.
    store.markRendering(agentId, "job-crashed-9")
    val fresh = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key"))

    val loaded = awaitUi(fresh.observe(agentId)) { it.loaded }

    val failed = loaded.state as ForgedScreenState.Failed
    assertEquals("job-crashed-9", failed.jobId)
    assertEquals("a tracker", failed.request)
    assertTrue(failed.message, failed.message.contains("crashed") || failed.message.contains("уронил"))
    assertNull(loaded.bundle)
    assertFalse(loaded.isReady)
    assertNull(store.renderingFlag(agentId))
    assertEquals(failed, store.load(agentId))
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  @Test
  fun `a rendering flag for another job leaves the Ready screen alone and is cleared`() = runTest(testDispatcher) {
    val fileName = store.writeBundle(agentId, "job-fine-1", ByteArray(8) { 2 })
    val ready = ForgedScreenState.Ready("job-fine-1", "a tracker", "FineScreen", 1, fileName)
    store.save(agentId, ready)
    store.markRendering(agentId, "job-older-0")
    val fresh = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key"))

    val loaded = awaitUi(fresh.observe(agentId)) { it.loaded }

    assertEquals(ready, loaded.state)
    assertTrue(loaded.isReady)
    assertArrayEquals(ByteArray(8) { 2 }, loaded.bundle)
    assertNull(store.renderingFlag(agentId))
    assertEquals(ready, store.load(agentId))
  }

  @Test
  fun `render hooks write and clear the flag for the agent`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.markRendering(agentId, "job-live-3")
    assertEquals("job-live-3", store.renderingFlag(agentId))
    repo.clearRendering(agentId)
    assertNull(store.renderingFlag(agentId))
  }

  // --- The own-interface switch -------------------------------------------------------------------

  @Test
  fun `switching on with no screen persists the switch and builds nothing until the brief`() = runTest(testDispatcher) {
    awaitLoaded()

    repo.setCustomUiEnabled(agentId, true, definition)

    val on = awaitUi { it.customUiEnabled }
    assertEquals(ForgedScreenState.None, on.state)
    assertTrue(store.isCustomUiEnabled(agentId))
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  @Test
  fun `the brief is what the forge is asked to build`() = runTest(testDispatcher) {
    awaitLoaded()

    repo.requestFromBrief(definition, brief)

    awaitUi { it.state is ForgedScreenState.Requested }
    val sent = bodyOf(gateway.submitBodies.single())
    assertEquals("Zorblax Counter", sent.getValue("agentName").jsonPrimitive.content)
    assertEquals(AgentUiRequest.fromBrief(definition, brief), sent.getValue("request").jsonPrimitive.content)
  }

  @Test
  fun `switching on over a Ready screen only flips the switch`() = runTest(testDispatcher) {
    storeReady("job-old-5")
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.isReady }

    repo.setCustomUiEnabled(agentId, true, definition)

    val on = awaitUi(flow) { it.customUiEnabled }
    assertTrue(on.showsCustomUi)
    assertEquals("job-old-5", on.state.jobId)
    assertTrue(store.isCustomUiEnabled(agentId))
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  @Test
  fun `switching on while a job is in flight does not submit a second one`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }

    repo.setCustomUiEnabled(agentId, true, definition)
    awaitUi { it.customUiEnabled }
    settle(100)

    assertEquals(1, gateway.submitBodies.size)
  }

  @Test
  fun `switching on after a failure keeps the failure, switching off drops the switch, neither asks the forge`() = runTest(testDispatcher) {
    store.save(agentId, ForgedScreenState.Failed("job-fail-1", "req", "boom"))
    val flow = repository(ForgeGatewayConfig("http://forge.test", "token", metaApiKey = "meta-key")).also { repo = it }.observe(agentId)
    awaitUi(flow) { it.state is ForgedScreenState.Failed }

    repo.setCustomUiEnabled(agentId, true, definition)
    val on = awaitUi(flow) { it.customUiEnabled }
    assertTrue("the card keeps offering Retry", on.state is ForgedScreenState.Failed)

    repo.setCustomUiEnabled(agentId, false, definition)
    val off = awaitUi(flow) { !it.customUiEnabled }
    assertFalse(off.showsCustomUi)
    assertFalse(store.isCustomUiEnabled(agentId))
    settle(100)
    assertEquals(emptyList<String>(), gateway.submitBodies.toList())
  }

  @Test
  fun `switching on without a definition only flips the switch`() = runTest(testDispatcher) {
    awaitLoaded()

    repo.setCustomUiEnabled(agentId, true, definition = null)

    awaitUi { it.customUiEnabled }
    assertTrue(store.isCustomUiEnabled(agentId))
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  // --- Removing -----------------------------------------------------------------------------------

  @Test
  fun `removing a ready screen clears the disk, the flags and the switch`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.setCustomUiEnabled(agentId, true, definition)
    awaitUi { it.customUiEnabled }
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }
    gateway.done(entryPoint = "TimerEntry", version = 2)
    awaitUi { it.state is ForgedScreenState.Ready }
    repo.markRendering(agentId, "job-fake-42")
    assertNotNull(store.readBundle(agentId, "job-fake-42.ktx"))
    assertTrue(store.isCustomUiEnabled(agentId))

    repo.remove(agentId)

    val cleared = awaitUi { it.state is ForgedScreenState.None }
    assertNull(cleared.bundle)
    assertFalse(cleared.customUiEnabled)
    assertTrue("the screen stays loaded, it is not the unloaded initial value", cleared.loaded)
    assertEquals(ForgedScreenState.None, store.load(agentId))
    assertNull(store.readBundle(agentId, "job-fake-42.ktx"))
    assertFalse(store.isCustomUiEnabled(agentId))
    assertNull(store.renderingFlag(agentId))
    assertFalse(agentDir().exists())
  }

  @Test
  fun `cancelling a job in flight stops the polling and forgets the job`() = runTest(testDispatcher) {
    awaitLoaded()
    repo.requestFromBrief(definition, brief)
    awaitUi { it.state is ForgedScreenState.Requested }
    awaitCalls(1) // the poller is parked in the handler

    repo.remove(agentId)
    awaitUi { it.state is ForgedScreenState.None }
    // The job finishes on the gateway AFTER the cancel: the app must not pick it up.
    gateway.done(entryPoint = "TooLateEntry", version = 1)
    settle()

    assertEquals(ForgedScreenState.None, ui.value.state)
    assertEquals(ForgedScreenState.None, store.load(agentId))
    assertFalse(gateway.log.any { it.contains("/v1/artifacts/") })
  }

  @Test
  fun `removing one agent leaves another agent's screen alone`() = runTest(testDispatcher) {
    awaitLoaded()
    val otherFile = store.writeBundle("other-agent", "job-other-1", byteArrayOf(5))
    store.save("other-agent", ForgedScreenState.Ready("job-other-1", "other", "OtherEntry", 3, otherFile))
    val other = repo.observe("other-agent")
    awaitUi(other) { it.isReady }

    repo.remove(agentId)
    settle(100)

    assertEquals("OtherEntry", (other.value.state as ForgedScreenState.Ready).entryPoint)
    assertArrayEquals(byteArrayOf(5), store.readBundle("other-agent", otherFile))
  }

  // --- The sample ---------------------------------------------------------------------------------

  @Test
  fun `the sample shows as Ready sample without touching disk or the gateway`() = runTest(testDispatcher) {
    awaitLoaded()
    val bytes = byteArrayOf(4, 5, 6, 7)

    repo.showSample(agentId, bytes, "Bundled sample request")

    val sample = awaitUi { (it.state as? ForgedScreenState.Ready)?.jobId == "sample" }
    val ready = sample.state as ForgedScreenState.Ready
    assertEquals(ForgedScreenRepository.SAMPLE_JOB_ID, ready.jobId)
    assertEquals(ForgedScreenRepository.SAMPLE_ENTRY_POINT, ready.entryPoint)
    assertEquals("Bundled sample request", ready.request)
    assertEquals(0, ready.version)
    assertEquals("sample.ktx", ready.fileName)
    assertArrayEquals(bytes, sample.bundle)
    assertTrue(sample.isReady)
    assertEquals(ForgedScreenState.None, store.load(agentId))
    assertFalse("nothing is written for a sample", agentDir().exists())
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  // --- The request text ---------------------------------------------------------------------------

  @Test
  fun `the request text carries the name, the description and a bounded excerpt of the instructions`() {
    val long = definition.copy(systemPrompt = "ALPHA" + "x".repeat(900) + "OMEGA")

    val request = AgentUiRequest.fromBrief(long, brief)

    assertTrue(request.contains("\"Zorblax Counter\""))
    assertTrue(request.contains("What the agent is for: Counts zorblax glasses every day"))
    assertTrue("the excerpt starts at the beginning of the instructions", request.contains("ALPHA"))
    assertFalse("the excerpt is cut, the tail never travels", request.contains("OMEGA"))
    assertTrue(request.contains(" ..."))
    assertTrue("the person's words travel whole", request.contains("The person's brief:\n[brief]\nGlasses drunk today and a button to add one\n[/brief]"))
    assertTrue(
      "the blocks are listed in the sheet's order, not the order they were ticked",
      request.indexOf(ScreenBlock.TOTAL.forForge) in 0 until request.indexOf(ScreenBlock.ADD_FORM.forForge),
    )
    assertTrue(request.contains("Layout: " + ScreenLayout.DASHBOARD.forForge))
    assertTrue("the card does not quote it as a typed change", AgentUiRequest.isAuto(request))
  }

  @Test
  fun `the brief reads back from its request, several lines and all`() {
    val written = ScreenBrief(
      text = "Glasses today\n- big counter\n- a reset",
      blocks = setOf(ScreenBlock.PHOTO, ScreenBlock.TOTAL),
      layout = ScreenLayout.LIST,
    )

    assertEquals(written, AgentUiRequest.briefOf(AgentUiRequest.fromBrief(definition, written)))
    assertEquals(brief, AgentUiRequest.briefOf(AgentUiRequest.fromBrief(definition, brief)))
  }

  @Test
  fun `a typed change has no brief to read back`() {
    assertNull(AgentUiRequest.briefOf("add a reset button"))
    assertNull(AgentUiRequest.briefOf("Design the main screen of the agent \"X\".\nWhat the agent is for: y"))
  }

  @Test
  fun `a blank description and blank instructions leave their lines out`() {
    val bare = definition.copy(description = "  ", systemPrompt = "")

    val request = AgentUiRequest.fromBrief(bare, brief)

    assertTrue(request.contains("Zorblax Counter"))
    assertFalse(request.contains("What the agent is for"))
    assertFalse(request.contains("Its instructions"))
  }
}
