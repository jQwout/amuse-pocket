package dev.amuse.forge.worker

import dev.amuse.forge.contract.ForgeRoutes
import dev.amuse.forge.contract.JobStatus
import dev.amuse.forge.contract.WorkerArtifactUpload
import dev.amuse.forge.contract.WorkerJob
import dev.amuse.forge.contract.WorkerStatusUpdate
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted gateway: the first poll hands out [jobs] one by one, then 204. */
private class FakeGateway(private val jobs: MutableList<WorkerJob>, private val failUploads: Int = 0) {
    val requests = mutableListOf<HttpRequestData>()
    private var uploadFailures = failUploads
    private var failNextPoll = false

    fun failNextPoll() { failNextPoll = true }

    val engine = MockEngine { request ->
        requests += request
        val path = request.url.encodedPath
        when {
            path == ForgeRoutes.WORKER_NEXT && request.method == HttpMethod.Get -> when {
                failNextPoll -> { failNextPoll = false; respond("boom", HttpStatusCode.BadGateway) }
                jobs.isEmpty() -> respond("", HttpStatusCode.NoContent)
                else -> respond(Json.encodeToString(jobs.removeAt(0)), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            path.endsWith("/artifact") && uploadFailures > 0 -> { uploadFailures--; respond("down", HttpStatusCode.ServiceUnavailable) }
            else -> respond("", HttpStatusCode.NoContent)
        }
    }

    fun statuses(): List<WorkerStatusUpdate> = requests.filter { it.url.encodedPath.endsWith("/status") }
        .map { Json.decodeFromString(WorkerStatusUpdate.serializer(), (it.body as TextContent).text) }

    fun uploads(): List<WorkerArtifactUpload> = requests.filter { it.url.encodedPath.endsWith("/artifact") }
        .map { Json.decodeFromString(WorkerArtifactUpload.serializer(), (it.body as TextContent).text) }

    fun client() = GatewayClient("https://gw.test/", "worker-token", engine, uploadRetryDelaysMs = listOf(1, 1), sleep = {})
}

class WorkerTest {
    private val job = WorkerJob("j1", "a1", "Water", "desc", "a water tracker", version = 1)

    @Test
    fun `nextJob long-polls with the bearer token and maps 204 to null`() = runTest {
        val gateway = FakeGateway(mutableListOf(job))
        val client = gateway.client()

        val first = client.nextJob(25)
        val second = client.nextJob(25)

        assertEquals(job, first)
        assertNull(second)
        val request = gateway.requests.first()
        assertEquals("https://gw.test/v1/worker/next?wait=25", request.url.toString())
        assertEquals("Bearer worker-token", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `a gateway error on poll is an exception the loop can catch`() = runTest {
        val gateway = FakeGateway(mutableListOf())
        gateway.failNextPoll()

        val error = assertFailsWith<GatewayException> { gateway.client().nextJob(25) }

        assertEquals(502, error.status)
    }

    @Test
    fun `a finished job goes accepted, working, upload with base64 bundle and source`() = runTest {
        val gateway = FakeGateway(mutableListOf(job))
        val outcome = ForgeOutcome.Done("WaterTrackerScreen", "KTX".toByteArray(), "fun WaterTrackerScreen() {}")
        val worker = Worker(gateway.client(), agent = { _, progress -> progress("Compiling the screen"); outcome }, sleep = {})

        worker.run(once = true)

        assertEquals(
            listOf(JobStatus.ACCEPTED, JobStatus.WORKING, JobStatus.WORKING),
            gateway.statuses().map { it.status },
        )
        val upload = gateway.uploads().single()
        assertEquals("WaterTrackerScreen", upload.entryPoint)
        assertEquals("fun WaterTrackerScreen() {}", upload.source)
        assertEquals("KTX", String(Base64.getDecoder().decode(upload.bundleBase64)))
        assertTrue(gateway.requests.any { it.url.encodedPath == ForgeRoutes.workerArtifact("j1") })
        assertEquals("Bearer worker-token", gateway.requests.last().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `needs_info and failed outcomes become status updates`() = runTest {
        val a = FakeGateway(mutableListOf(job))
        Worker(a.client(), agent = { _, _ -> ForgeOutcome.NeedsInfo("Which units?") }, sleep = {}).run(once = true)
        val b = FakeGateway(mutableListOf(job))
        Worker(b.client(), agent = { _, _ -> ForgeOutcome.Failed("no luck") }, sleep = {}).run(once = true)

        assertEquals(WorkerStatusUpdate(JobStatus.NEEDS_INFO, question = "Which units?"), a.statuses().last())
        assertEquals(WorkerStatusUpdate(JobStatus.FAILED, message = "no luck"), b.statuses().last())
        assertTrue(a.uploads().isEmpty() && b.uploads().isEmpty())
    }

    @Test
    fun `a crashing agent reports failed instead of killing the worker`() = runTest {
        val gateway = FakeGateway(mutableListOf(job))
        val worker = Worker(gateway.client(), agent = { _, _ -> error("kaboom") }, sleep = {})

        worker.run(once = true)

        assertEquals(JobStatus.FAILED, gateway.statuses().last().status)
    }

    @Test
    fun `upload is retried and a permanent failure is reported as failed`() = runTest {
        val flaky = FakeGateway(mutableListOf(job), failUploads = 2)
        val done = ForgeOutcome.Done("XScreen", ByteArray(1), "src")
        Worker(flaky.client(), agent = { _, _ -> done }, sleep = {}).run(once = true)
        assertEquals(3, flaky.uploads().size)
        assertTrue(flaky.statuses().none { it.status == JobStatus.FAILED })

        val dead = FakeGateway(mutableListOf(job), failUploads = 99)
        Worker(dead.client(), agent = { _, _ -> done }, sleep = {}).run(once = true)
        assertEquals(JobStatus.FAILED, dead.statuses().last().status)
    }

    @Test
    fun `poll errors back off and retry instead of ending the loop`() = runTest {
        val gateway = FakeGateway(mutableListOf(job))
        gateway.failNextPoll()
        val sleeps = mutableListOf<Long>()
        val worker = Worker(
            gateway.client(), agent = { _, _ -> ForgeOutcome.NeedsInfo("q") },
            backoffStartMs = 100, backoffMaxMs = 400, sleep = { sleeps += it },
        )

        worker.run(once = true)

        assertEquals(listOf(100L), sleeps)
        assertEquals(JobStatus.NEEDS_INFO, gateway.statuses().last().status)
    }

    @Test
    fun `config reads the environment, defaults the model and wants no Meta key of its own`() {
        val env = mapOf("FORGE_GATEWAY_URL" to "https://gw.test/", "FORGE_WORKER_TOKEN" to "t", "FORGE_DIR" to "build")
        val config = WorkerConfig.fromEnv(env)

        assertEquals("muse-spark-1.3", config.model)
        assertEquals("https://api.meta.ai/v1", config.metaBaseUrl)
        assertEquals("https://gw.test", config.gatewayUrl)
        assertEquals("medium", config.reasoningEffort)
        assertTrue(!config.toString().contains("token"))
        assertEquals(null, WorkerConfig.fromEnv(env + ("FORGE_REASONING_EFFORT" to "off")).reasoningEffort)
        val error = assertFailsWith<IllegalArgumentException> { WorkerConfig.fromEnv(env - "FORGE_WORKER_TOKEN") }
        assertTrue(error.message!!.contains("FORGE_WORKER_TOKEN"))
    }
}

class SelfTestTest {
    @Test
    fun `self-test writes builds and cleans up the generated screen`() {
        val dir = makeForgeDir()
        val result = SelfTest.run(dir, FakeBuild(mutableListOf(true), dir))

        assertTrue(result.ok, result.report)
        assertEquals(9, result.bundleBytes)
        assertTrue(!java.nio.file.Files.exists(dir.resolve("$GENERATED_DIR/${SelfTest.SAMPLE_NAME}.kt")))
    }

    @Test
    fun `self-test reports a failed build`() {
        val dir = makeForgeDir()
        val result = SelfTest.run(dir, FakeBuild(mutableListOf(false), dir))

        assertTrue(!result.ok)
        assertTrue(result.report.contains("build_bundle failed"))
    }
}
