package com.aiopenpocket.ui.agents.detail

// @trace cloud-forged-screens-40d702

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.DefaultAgentResolver
import com.aiopenpocket.agent.core.agent.MiniAgentCatalog
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.agent.displayName
import com.aiopenpocket.agent.core.agent.toDefinition
import com.aiopenpocket.agent.core.forge.ForgeGatewayConfig
import com.aiopenpocket.agent.core.forge.ForgedScreenState
import com.aiopenpocket.agent.core.forge.ForgedScreenStore
import com.aiopenpocket.agent.core.forge.ScreenForgeClient
import com.aiopenpocket.agent.core.hook.AgentHookRepository
import com.aiopenpocket.agent.core.mcp.RiskClass
import com.aiopenpocket.agent.core.mcp.Tool
import com.aiopenpocket.agent.core.mcp.ToolRegistry
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.core.mcp.toolSchema
import com.aiopenpocket.agent.core.skill.SkillRegistry
import com.aiopenpocket.agent.core.skill.UserSkillRepository
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.data.repository.AgentFilesRepository
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.agent.core.plan.persistence.PlanRepository
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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
 * The cloud-forged-screen flow of [AgentDetailViewModel]: ask, follow the job, answer its question,
 * receive the bundle, forget it. Same wiring as [AgentDetailViewModelTest] (real in-memory Room, real
 * [ForgedScreenStore] on the Robolectric `filesDir`), with a scripted fake gateway behind a Ktor
 * `MockEngine`.
 *
 * Two mechanics worth knowing:
 *  - the gateway's `GET /v1/jobs/{id}` answers are fed one at a time through [FakeGateway.statusReplies],
 *    and the handler SUSPENDS on an empty channel. That is what makes every intermediate state
 *    (Requested, Working, NeedsInfo) observable: a StateFlow conflates, so a free-running mock would let
 *    the test see only the last state. It also makes "polling stopped" measurable: a poller that goes on
 *    would enter the handler again and bump [FakeGateway.statusCalls].
 *  - the store and the client work on `Dispatchers.IO`, which the test scheduler does not track, so the
 *    test waits for state in REAL time ([awaitState]); the `delay` between polls (10 ms here) is virtual
 *    and the `runTest` loop skips it.
 *
 * Every assertion on state is on tokens only the mock can produce (job id, question, entry point,
 * progress line), never on a state the initial load already satisfies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentDetailViewModelForgeTest {

  private val testDispatcher = UnconfinedTestDispatcher()
  private val application = RuntimeEnvironment.getApplication()

  private lateinit var db: AppDatabase
  private lateinit var userAgentRepository: UserAgentRepository
  private lateinit var store: ForgedScreenStore
  private lateinit var gateway: FakeGateway
  private lateinit var vm: AgentDetailViewModel

  private val agentId = "forge-agent"

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
            respond("""{"jobId":"$jobId"}""", HttpStatusCode.Accepted, json)
          } else {
            respond("denied", submitStatus)
          }
        }

        request.method == HttpMethod.Post && path.endsWith("/answer") -> {
          answerBodies += (request.body as TextContent).text
          respond("""{"jobId":"$jobId","status":"queued"}""", HttpStatusCode.OK, json)
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

  private class FakeTool(override val name: String) : Tool {
    override val riskClass = RiskClass.READ
    override val description = "fake tool $name"
    override val schema = toolSchema(name, description) {}
    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult = ToolResult.Success("ok:$name")
  }

  @Before
  fun setUp() {
    AppLanguage.code = AppLanguage.EN
    Dispatchers.setMain(testDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .setQueryExecutor(testDispatcher.asExecutor())
      .setTransactionExecutor(testDispatcher.asExecutor())
      .build()
    val hooks = AgentHookRepository(db.agentHookDao())
    userAgentRepository = UserAgentRepository(db, db.userAgentDao(), hooks)
    store = ForgedScreenStore(application)
    gateway = FakeGateway()
    vm = viewModel(gateway, ForgeGatewayConfig("http://forge.test", "token"))
  }

  @After
  fun tearDown() {
    // Stop the pollers (one may be parked in the handler's receive()) before the next test starts.
    vm.viewModelScope.cancel()
    gateway.statusReplies.close()
    Dispatchers.resetMain()
    AppLanguage.code = AppLanguage.RU
    db.close()
  }

  private fun viewModel(gateway: FakeGateway, config: ForgeGatewayConfig): AgentDetailViewModel {
    val hooks = AgentHookRepository(db.agentHookDao())
    val userSkills = UserSkillRepository(db.userSkillDao())
    return AgentDetailViewModel(
      agentResolver = DefaultAgentResolver(userAgentRepository),
      userAgentRepository = userAgentRepository,
      toolRegistry = ToolRegistry(setOf(FakeTool("memory_tool"))),
      skillRegistry = SkillRegistry(emptySet(), userSkills),
      agentHookRepository = hooks,
      heartbeatDao = db.heartbeatDao(),
      agentFilesRepository = AgentFilesRepository(application),
      planRepository = PlanRepository(db, db.planDao()) { 10_000L },
      userSkillRepository = userSkills,
      forgedScreenStore = store,
      screenForgeClient = ScreenForgeClient(gateway.engine) { config },
      application = application,
    ).also { it.forgePollIntervalMs = 10L }
  }

  private suspend fun createAgent(): AgentDefinition {
    val preset = MiniAgentCatalog.get("budget")!!.toDefinition()
    val agent = preset.copy(
      id = agentId,
      source = AgentSource.USER,
      status = AgentStatus.DRAFT,
      configVersion = 0L,
      toolNames = setOf("memory_tool"),
      skillIds = emptySet(),
      memoryPolicy = preset.memoryPolicy.copy(namespace = "agent:$agentId:"),
    )
    userAgentRepository.saveDraft(agent)
    userAgentRepository.publish(agentId)
    return agent
  }

  /** Opens the screen and waits until the forge part of the state has loaded (configured is true only after `loadForged`). */
  private suspend fun open(): AgentDetailState {
    vm.select(agentId)
    return awaitState { it.definition != null && it.forgeConfigured }
  }

  /** Waits in REAL time: the store and the client run on IO, which the test scheduler does not track. */
  private suspend fun awaitState(timeoutMs: Long = 10_000, predicate: (AgentDetailState) -> Boolean): AgentDetailState =
    withContext(Dispatchers.Default) { withTimeout(timeoutMs) { vm.state.first(predicate) } }

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
   * snapshot state, and in this harness a background thread was seen reading the Russian default even
   * with English set on the test thread (flaky, about one run in three). The wording is not what these
   * tests guard, so a message is matched in both languages.
   */
  private fun assertSaysEither(message: String, ru: String, en: String) {
    assertTrue(message, message.contains(ru) || message.contains(en))
  }

  private fun bodyOf(raw: String) = Json.parseToJsonElement(raw).jsonObject

  // --- The happy path -----------------------------------------------------------------------------

  @Test
  fun `asking for a screen is sent, persisted as Requested, followed through Working and ends Ready with the bundle on disk`() = runTest(testDispatcher) {
    val agent = createAgent()
    open()

    vm.updateForgeDraft("  water tracker with 8 glasses  ")
    vm.buildScreen()

    // 1. Requested: the id is the mock's, the request is trimmed, the draft is consumed.
    val requested = awaitState { it.forged is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", "water tracker with 8 glasses", null), requested.forged)
    assertEquals("", requested.forgedDraft)
    val sent = bodyOf(gateway.submitBodies.single())
    assertEquals(agentId, sent.getValue("agentId").jsonPrimitive.content)
    assertEquals(agent.displayName, sent.getValue("agentName").jsonPrimitive.content)
    assertEquals("water tracker with 8 glasses", sent.getValue("request").jsonPrimitive.content)
    assertFalse("previousJobId" in sent)
    // Persisted BEFORE the first poll answers: a restart now would resume this job.
    assertEquals(ForgedScreenState.Requested("job-fake-42", "water tracker with 8 glasses", null), store.load(agentId))

    // 2. Working with the worker's progress line.
    gateway.working(message = "compiling-the-glasses")
    val working = awaitState { (it.forged as? ForgedScreenState.Working)?.message == "compiling-the-glasses" }
    assertEquals(ForgedScreenState.Working("job-fake-42", "water tracker with 8 glasses", "compiling-the-glasses"), working.forged)
    assertEquals(working.forged, store.load(agentId))

    // 3. Done: the bundle is downloaded, verified, written, and the state names the mock's entry point.
    gateway.done(entryPoint = "GlassesScreenEntry", version = 4)
    val ready = awaitState { it.forged is ForgedScreenState.Ready }
    assertEquals(
      ForgedScreenState.Ready("job-fake-42", "water tracker with 8 glasses", "GlassesScreenEntry", 4, "job-fake-42.ktx"),
      ready.forged,
    )
    assertArrayEquals(gateway.bundle, ready.forgedBundle)
    assertArrayEquals(gateway.bundle, store.readBundle(agentId, "job-fake-42.ktx"))
    assertEquals(ready.forged, store.load(agentId))
    assertTrue("GET /v1/artifacts/job-fake-42.ktx" in gateway.log)
    assertEquals(listOf("/v1/jobs/job-fake-42", "/v1/jobs/job-fake-42"), gateway.statusPaths.toList())

    // 4. Polling ended with the job: nobody asks again.
    settle()
    assertEquals(2, gateway.statusCalls.get())
  }

  @Test
  fun `a state the screen reopens on is read back from disk, bundle included, without asking the gateway`() = runTest(testDispatcher) {
    createAgent()
    val ready = ForgedScreenState.Ready("job-disk-1", "stored request", "StoredEntry", 9, "job-disk-1.ktx")
    store.save(agentId, ready)
    store.writeBundle(agentId, "job-disk-1", byteArrayOf(7, 7, 7))

    val state = open()

    assertEquals(ready, state.forged)
    assertArrayEquals(byteArrayOf(7, 7, 7), state.forgedBundle)
    settle()
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  // --- needs_info and the answer ------------------------------------------------------------------

  @Test
  fun `needs_info shows the question and stops polling, the answer is posted and polling resumes`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("budget donut")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }

    gateway.needsInfo("Which-currency-mock?")
    val asked = awaitState { it.forged is ForgedScreenState.NeedsInfo }
    assertEquals(ForgedScreenState.NeedsInfo("job-fake-42", "budget donut", "Which-currency-mock?"), asked.forged)
    assertEquals(asked.forged, store.load(agentId))

    // Polling stopped: with a live poller a second status request would already be parked in the handler.
    settle()
    assertEquals(1, gateway.statusCalls.get())

    vm.answerForge("  euros  ")

    val resumed = awaitState { it.forged is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", "budget donut"), resumed.forged)
    val answer = bodyOf(gateway.answerBodies.single())
    assertEquals("euros", answer.getValue("text").jsonPrimitive.content)
    assertTrue("POST /v1/jobs/job-fake-42/answer" in gateway.log)
    // Polling is back: the second status request arrives, and its answer moves the state on.
    awaitCalls(2)
    gateway.done(entryPoint = "DonutEntry", version = 1)
    val ready = awaitState { it.forged is ForgedScreenState.Ready }
    assertEquals("DonutEntry", (ready.forged as ForgedScreenState.Ready).entryPoint)
  }

  @Test
  fun `an answer is ignored unless the agent is asking, and a blank one is ignored always`() = runTest(testDispatcher) {
    createAgent()
    open()

    vm.answerForge("nobody asked")
    settle(100)
    assertEquals(emptyList<String>(), gateway.log.toList())

    store.save(agentId, ForgedScreenState.NeedsInfo("job-ask-1", "req", "q?"))
    // `vm` has no agent open yet, but a fresh model keeps the stored NeedsInfo the only thing in play.
    val second = viewModel(gateway, ForgeGatewayConfig("http://forge.test", "token"))
    try {
      second.select(agentId)
      withContext(Dispatchers.Default) { withTimeout(10_000) { second.state.first { it.forged is ForgedScreenState.NeedsInfo } } }
      second.answerForge("   ")
      settle(100)
      assertTrue(gateway.answerBodies.isEmpty())
    } finally {
      second.viewModelScope.cancel()
    }
  }

  // --- Resuming -----------------------------------------------------------------------------------

  @Test
  fun `select on a stored Working state resumes polling that job`() = runTest(testDispatcher) {
    createAgent()
    store.save(agentId, ForgedScreenState.Working("job-stored-9", "stored request", null))

    vm.select(agentId)

    // The poller asks for the STORED job, nothing was submitted.
    awaitCalls(1)
    assertEquals(listOf("/v1/jobs/job-stored-9"), gateway.statusPaths.toList())
    assertTrue(gateway.submitBodies.isEmpty())
    gateway.working(message = "resumed-after-restart", id = "job-stored-9")
    val state = awaitState { (it.forged as? ForgedScreenState.Working)?.message == "resumed-after-restart" }
    assertEquals("job-stored-9", state.forged.jobId)
  }

  @Test
  fun `select on a stored Requested state resumes polling too`() = runTest(testDispatcher) {
    createAgent()
    store.save(agentId, ForgedScreenState.Requested("job-stored-3", "stored request"))

    vm.select(agentId)

    awaitCalls(1)
    assertEquals(listOf("/v1/jobs/job-stored-3"), gateway.statusPaths.toList())
  }

  @Test
  fun `select on stored NeedsInfo or Failed does not poll`() = runTest(testDispatcher) {
    createAgent()
    store.save(agentId, ForgedScreenState.NeedsInfo("job-ask-1", "req", "q?"))
    open()
    settle()
    assertEquals(0, gateway.statusCalls.get())

    store.save(agentId, ForgedScreenState.Failed("job-fail-1", "req", "boom"))
    val other = viewModel(gateway, ForgeGatewayConfig("http://forge.test", "token"))
    try {
      other.select(agentId)
      withContext(Dispatchers.Default) { withTimeout(10_000) { other.state.first { (it.forged as? ForgedScreenState.Failed)?.message == "boom" } } }
      settle()
      assertEquals(0, gateway.statusCalls.get())
    } finally {
      other.viewModelScope.cancel()
    }
  }

  // --- Update / retry -----------------------------------------------------------------------------

  @Test
  fun `updating a ready screen sends the previous job id and the new request`() = runTest(testDispatcher) {
    createAgent()
    store.save(agentId, ForgedScreenState.Ready("job-old-5", "first version", "OldEntry", 1, "job-old-5.ktx"))
    store.writeBundle(agentId, "job-old-5", byteArrayOf(1))
    open()

    vm.updateForgeDraft("make the glasses blue")
    vm.updateScreen()

    val requested = awaitState { it.forged is ForgedScreenState.Requested }
    assertEquals(ForgedScreenState.Requested("job-fake-42", "make the glasses blue", "job-old-5"), requested.forged)
    val sent = bodyOf(gateway.submitBodies.single())
    assertEquals("job-old-5", sent.getValue("previousJobId").jsonPrimitive.content)
    assertEquals("make the glasses blue", sent.getValue("request").jsonPrimitive.content)
  }

  @Test
  fun `try again after a failure re-sends the last request without retyping it`() = runTest(testDispatcher) {
    createAgent()
    store.save(agentId, ForgedScreenState.Failed("job-fail-1", "the original ask", "boom"))
    open()

    vm.buildScreen()

    val requested = awaitState { it.forged is ForgedScreenState.Requested }
    assertEquals("the original ask", requested.forged.lastRequest)
    assertEquals("the original ask", bodyOf(gateway.submitBodies.single()).getValue("request").jsonPrimitive.content)
  }

  @Test
  fun `a build with nothing typed and nothing to retry sends nothing`() = runTest(testDispatcher) {
    createAgent()
    open()

    vm.updateForgeDraft("   ")
    vm.buildScreen()
    settle(100)

    assertEquals(emptyList<String>(), gateway.log.toList())
    assertEquals(ForgedScreenState.None, vm.state.value.forged)
  }

  // --- Removing -----------------------------------------------------------------------------------

  @Test
  fun `removing a ready screen clears the disk and the state`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("timer")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }
    gateway.done(entryPoint = "TimerEntry", version = 2)
    awaitState { it.forged is ForgedScreenState.Ready }
    assertNotNull(store.readBundle(agentId, "job-fake-42.ktx"))

    vm.removeScreen()

    val cleared = awaitState { it.forged is ForgedScreenState.None }
    assertNull(cleared.forgedBundle)
    assertEquals(ForgedScreenState.None, store.load(agentId))
    assertNull(store.readBundle(agentId, "job-fake-42.ktx"))
    assertFalse(File(File(application.filesDir, ForgedScreenStore.DIR_NAME), agentId).exists())
  }

  @Test
  fun `cancelling a job in flight stops the polling and forgets the job`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("timer")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }
    awaitCalls(1) // the poller is parked in the handler

    vm.removeScreen()
    awaitState { it.forged is ForgedScreenState.None }
    // The job finishes on the gateway AFTER the cancel: the app must not pick it up.
    gateway.done(entryPoint = "TooLateEntry", version = 1)
    settle()

    assertEquals(ForgedScreenState.None, vm.state.value.forged)
    assertEquals(ForgedScreenState.None, store.load(agentId))
    assertFalse(gateway.log.any { it.contains("/v1/artifacts/") })
  }

  // --- Failures -----------------------------------------------------------------------------------

  @Test
  fun `five transport failures in a row end the job as Failed`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("chart")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }
    // More than five are queued: a poller that gave up one call late would be caught by the exact count.
    repeat(8) { gateway.transportFailure() }

    val failed = awaitState { it.forged is ForgedScreenState.Failed }

    val state = failed.forged as ForgedScreenState.Failed
    assertEquals("job-fake-42", state.jobId)
    assertEquals("chart", state.request)
    assertTrue(state.message, state.message.contains("socket reset by mock"))
    assertEquals(state, store.load(agentId))
    settle()
    assertEquals(5, gateway.statusCalls.get())
  }

  @Test
  fun `a good answer in between resets the failure count`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("chart")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }
    repeat(4) { gateway.transportFailure() }
    gateway.working(message = "still-alive-after-4")
    repeat(4) { gateway.transportFailure() }
    gateway.done(entryPoint = "SurvivorEntry", version = 1)

    val ready = awaitState { it.forged is ForgedScreenState.Ready }

    assertEquals("SurvivorEntry", (ready.forged as ForgedScreenState.Ready).entryPoint)
    assertEquals(10, gateway.statusCalls.get())
  }

  @Test
  fun `a job the gateway reports as failed ends polling with the gateway's message`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("chart")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }

    gateway.failedJob("kotlinc-exploded-mock")

    val failed = awaitState { it.forged is ForgedScreenState.Failed }
    assertEquals(ForgedScreenState.Failed("job-fake-42", "chart", "kotlinc-exploded-mock"), failed.forged)
    assertEquals(failed.forged, store.load(agentId))
    settle()
    assertEquals(1, gateway.statusCalls.get())
  }

  @Test
  fun `done without an artifact is a Failed, not a crash`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("chart")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }

    gateway.reply("""{"jobId":"job-fake-42","status":"done"}""")

    val failed = awaitState { it.forged is ForgedScreenState.Failed }
    assertSaysEither((failed.forged as ForgedScreenState.Failed).message, "без бандла", "without a bundle")
  }

  @Test
  fun `a bundle that fails the checksum is not stored and the job ends Failed`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.updateForgeDraft("chart")
    vm.buildScreen()
    awaitState { it.forged is ForgedScreenState.Requested }

    gateway.done(entryPoint = "SwappedEntry", version = 1, sha = "0".repeat(64))

    val failed = awaitState { it.forged is ForgedScreenState.Failed }
    val message = (failed.forged as ForgedScreenState.Failed).message
    assertSaysEither(message, "контрольная сумма", "checksum")
    assertNull(failed.forgedBundle)
    assertNull(store.readBundle(agentId, "job-fake-42.ktx"))
  }

  @Test
  fun `a refused submit becomes Failed with no job id and a hint about Settings`() = runTest(testDispatcher) {
    createAgent()
    open()
    gateway.submitStatus = HttpStatusCode.Unauthorized
    vm.updateForgeDraft("chart")

    vm.buildScreen()

    val failed = awaitState { it.forged is ForgedScreenState.Failed }
    val state = failed.forged as ForgedScreenState.Failed
    assertNull(state.jobId)
    assertEquals("chart", state.request)
    assertSaysEither(state.message, "Настройках", "Settings")
    assertEquals(state, store.load(agentId))
    settle(100)
    assertEquals(0, gateway.statusCalls.get())
  }

  @Test
  fun `without a gateway address and token the screen reports the forge as not configured`() = runTest(testDispatcher) {
    createAgent()
    vm.viewModelScope.cancel()
    vm = viewModel(gateway, ForgeGatewayConfig("", ""))
    vm.select(agentId)
    // `forgeConfigured` is false from the start, so wait on the definition and a tick of IO instead.
    awaitState { it.definition != null && !it.isLoading }
    settle(100)

    assertFalse(vm.state.value.forgeConfigured)
    vm.updateForgeDraft("chart")
    vm.buildScreen()
    val failed = awaitState { it.forged is ForgedScreenState.Failed }
    assertSaysEither((failed.forged as ForgedScreenState.Failed).message, "адрес кузницы и токен", "forge address and token")
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  // --- The sample ---------------------------------------------------------------------------------

  @Test
  fun `the bundled sample renders as Ready sample without touching disk or the gateway`() = runTest(testDispatcher) {
    createAgent()
    open()

    vm.loadSampleScreen()

    val state = awaitState { (it.forged as? ForgedScreenState.Ready)?.jobId == "sample" }
    val ready = state.forged as ForgedScreenState.Ready
    assertEquals(AgentDetailViewModel.SAMPLE_ENTRY_POINT, ready.entryPoint)
    assertEquals(0, ready.version)
    assertEquals("sample.ktx", ready.fileName)
    val shipped = File("src/debug/assets/ketoy/hello.ktx").readBytes()
    assertTrue("the sample asset must not be empty", shipped.isNotEmpty())
    assertArrayEquals(shipped, state.forgedBundle)
    assertEquals(ForgedScreenState.None, store.load(agentId))
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  @Test
  fun `a rendering flag left behind takes the screen down instead of composing it again`() = runTest(testDispatcher) {
    createAgent()
    val fileName = store.writeBundle(agentId, "job-crashed-9", ByteArray(8) { 1 })
    store.save(agentId, ForgedScreenState.Ready("job-crashed-9", "a tracker", "CrashScreen", 1, fileName))
    // What the section writes right before the bundle composes; the process then died.
    store.markRendering(agentId, "job-crashed-9")

    val state = open()

    val failed = state.forged as ForgedScreenState.Failed
    assertEquals("job-crashed-9", failed.jobId)
    assertEquals("a tracker", failed.request)
    assertTrue(failed.message, failed.message.contains("crashed") || failed.message.contains("уронил"))
    assertNull(state.forgedBundle)
    assertNull(store.renderingFlag(agentId))
    assertEquals(failed, store.load(agentId))
    assertEquals(emptyList<String>(), gateway.log.toList())
  }

  @Test
  fun `a flag for another job leaves a Ready screen alone`() = runTest(testDispatcher) {
    createAgent()
    val fileName = store.writeBundle(agentId, "job-fine-1", ByteArray(8) { 2 })
    store.save(agentId, ForgedScreenState.Ready("job-fine-1", "a tracker", "FineScreen", 1, fileName))
    store.markRendering(agentId, "job-older-0")

    val state = open()

    assertEquals("job-fine-1", (state.forged as ForgedScreenState.Ready).jobId)
    assertNull(store.renderingFlag(agentId))
  }

  @Test
  fun `render hooks write and clear the flag for the current agent`() = runTest(testDispatcher) {
    createAgent()
    open()
    vm.forgedRenderStarted("job-live-3")
    assertEquals("job-live-3", store.renderingFlag(agentId))
    vm.forgedRenderStopped()
    assertNull(store.renderingFlag(agentId))
  }

}
