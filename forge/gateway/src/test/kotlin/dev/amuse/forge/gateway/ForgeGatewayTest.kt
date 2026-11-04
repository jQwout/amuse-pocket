package dev.amuse.forge.gateway

import dev.amuse.forge.contract.AnswerRequest
import dev.amuse.forge.contract.CreateJobRequest
import dev.amuse.forge.contract.CreateJobResponse
import dev.amuse.forge.contract.ForgeRoutes
import dev.amuse.forge.contract.JobStatus
import dev.amuse.forge.contract.JobView
import dev.amuse.forge.contract.WorkerArtifactUpload
import dev.amuse.forge.contract.WorkerJob
import dev.amuse.forge.contract.WorkerStatusUpdate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeGatewayTest {

    private val tokens = ForgeTokens(client = "client-secret", worker = "worker-secret")
    private val bundle = "KTOY fake bundle bytes".toByteArray()
    private val metaKey = "LLM_person_own_key_123"

    private fun ApplicationTestBuilder.start(dataDir: File = Files.createTempDirectory("forge-test").toFile()): HttpClient {
        val store = JobStore(dataDir)
        application { forgeModule(store, tokens, longPollMaxSeconds = 1) }
        return createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private suspend fun HttpClient.createJob(
        agentId: String = "agent_1",
        request: String = "a water tracker with 8 glasses",
        previousJobId: String? = null,
    ): String {
        val response = post(ForgeRoutes.JOBS) {
            bearerAuth(tokens.client)
            header(ForgeRoutes.META_KEY_HEADER, metaKey)
            contentType(ContentType.Application.Json)
            setBody(CreateJobRequest(agentId, "Hydration coach", "keeps me drinking", request, previousJobId))
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        return response.body<CreateJobResponse>().jobId
    }

    private suspend fun HttpClient.view(jobId: String): JobView =
        get(ForgeRoutes.job(jobId)) { bearerAuth(tokens.client) }.body()

    private suspend fun HttpClient.workerNext(): WorkerJob? {
        val response = get("${ForgeRoutes.WORKER_NEXT}?wait=0") { bearerAuth(tokens.worker) }
        return if (response.status == HttpStatusCode.NoContent) null else response.body<WorkerJob>()
    }

    private suspend fun HttpClient.workerStatus(jobId: String, update: WorkerStatusUpdate): HttpResponse =
        post(ForgeRoutes.workerStatus(jobId)) {
            bearerAuth(tokens.worker)
            contentType(ContentType.Application.Json)
            setBody(update)
        }

    private suspend fun HttpClient.workerArtifact(jobId: String, entryPoint: String = "WaterTrackerScreen"): JobView =
        post(ForgeRoutes.workerArtifact(jobId)) {
            bearerAuth(tokens.worker)
            contentType(ContentType.Application.Json)
            setBody(WorkerArtifactUpload(entryPoint, "fun $entryPoint() {}", Base64.getEncoder().encodeToString(bundle)))
        }.body()

    @Test
    fun `health needs no token, everything else does`() = testApplication {
        val client = start()
        assertEquals(HttpStatusCode.OK, client.get(ForgeRoutes.HEALTH).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ForgeRoutes.job("x")).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ForgeRoutes.WORKER_NEXT).status)
        // The client token opens the app side only, not the worker side (and the other way round).
        assertEquals(HttpStatusCode.Unauthorized, client.get("${ForgeRoutes.WORKER_NEXT}?wait=0") { bearerAuth(tokens.client) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get(ForgeRoutes.job("x")) { bearerAuth(tokens.worker) }.status)
    }

    @Test
    fun `a job walks from queued to done and the bundle comes back verified`() = testApplication {
        val client = start()
        val jobId = client.createJob()
        assertEquals(JobStatus.QUEUED, client.view(jobId).status)

        val handed = assertNotNull(client.workerNext())
        assertEquals(jobId, handed.jobId)
        assertEquals(1, handed.version)
        assertEquals("a water tracker with 8 glasses", handed.request)
        assertEquals(JobStatus.ACCEPTED, client.view(jobId).status)
        assertNull(client.workerNext(), "the same job must not be handed out twice")

        client.workerStatus(jobId, WorkerStatusUpdate(JobStatus.WORKING, message = "writing the screen"))
        assertEquals("writing the screen", client.view(jobId).message)

        val done = client.workerArtifact(jobId)
        assertEquals(JobStatus.DONE, done.status)
        val artifact = assertNotNull(done.artifact)
        assertEquals("WaterTrackerScreen", artifact.entryPoint)
        assertEquals(1, artifact.version)
        assertEquals(JobStore.sha256Hex(bundle), artifact.sha256)
        assertEquals(ForgeRoutes.bundle(jobId), artifact.url)

        val download = client.get(artifact.url) { bearerAuth(tokens.client) }
        assertEquals(HttpStatusCode.OK, download.status)
        assertTrue(bundle.contentEquals(download.readRawBytes()))
        val etag = assertNotNull(download.headers[HttpHeaders.ETag])
        val cached = client.get(artifact.url) { bearerAuth(tokens.client); header(HttpHeaders.IfNoneMatch, etag) }
        assertEquals(HttpStatusCode.NotModified, cached.status)
    }

    @Test
    fun `needs_info sends the question to the app and the answer back to the worker`() = testApplication {
        val client = start()
        val jobId = client.createJob()
        client.workerNext()
        client.workerStatus(jobId, WorkerStatusUpdate(JobStatus.NEEDS_INFO, question = "How many glasses?"))
        val asked = client.view(jobId)
        assertEquals(JobStatus.NEEDS_INFO, asked.status)
        assertEquals("How many glasses?", asked.question)
        assertNull(client.workerNext(), "a job waiting for the person is not in the queue")

        val answered = client.post(ForgeRoutes.answer(jobId)) {
            bearerAuth(tokens.client)
            header(ForgeRoutes.META_KEY_HEADER, metaKey)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest("Eight"))
        }.body<JobView>()
        assertEquals(JobStatus.QUEUED, answered.status)
        assertNull(answered.question)

        val again = assertNotNull(client.workerNext())
        assertEquals(listOf("How many glasses?" to "Eight"), again.exchanges.map { it.question to it.answer })
    }

    @Test
    fun `an update carries the previous source and counts the version up`() = testApplication {
        val client = start()
        val first = client.createJob()
        client.workerNext()
        client.workerArtifact(first, entryPoint = "WaterTrackerScreen")

        val second = client.createJob(request = "make the glasses blue", previousJobId = first)
        val handed = assertNotNull(client.workerNext())
        assertEquals(second, handed.jobId)
        assertEquals(2, handed.version)
        assertEquals("WaterTrackerScreen", handed.previousEntryPoint)
        assertEquals("fun WaterTrackerScreen() {}", handed.previousSource)
    }

    @Test
    fun `worker cannot report done through the status route and needs_info needs a question`() = testApplication {
        val client = start()
        val jobId = client.createJob()
        client.workerNext()
        assertEquals(HttpStatusCode.BadRequest, client.workerStatus(jobId, WorkerStatusUpdate(JobStatus.DONE)).status)
        assertEquals(HttpStatusCode.BadRequest, client.workerStatus(jobId, WorkerStatusUpdate(JobStatus.NEEDS_INFO)).status)
        assertEquals(HttpStatusCode.NotFound, client.workerStatus("job_missing", WorkerStatusUpdate(JobStatus.WORKING)).status)
    }

    @Test
    fun `jobs survive a restart of the store`() = testApplication {
        val dataDir = Files.createTempDirectory("forge-restart").toFile()
        val client = start(dataDir)
        val jobId = client.createJob()
        client.workerNext()
        client.workerArtifact(jobId)

        val reloaded = JobStore(dataDir)
        val job = kotlinx.coroutines.runBlocking { reloaded.get(jobId) }
        assertEquals(JobStatus.DONE, assertNotNull(job).status)
        assertTrue(bundle.contentEquals(assertNotNull(kotlinx.coroutines.runBlocking { reloaded.bundleBytes(jobId) })))
    }

    @Test
    fun `the person's Meta key passes through to the worker and never reaches the disk`() = testApplication {
        val dataDir = Files.createTempDirectory("forge-key").toFile()
        val client = start(dataDir)
        val withoutKey = client.post(ForgeRoutes.JOBS) {
            bearerAuth(tokens.client)
            contentType(ContentType.Application.Json)
            setBody(CreateJobRequest("agent_1", "Hydration coach", "keeps me drinking", "a tracker"))
        }
        assertEquals(HttpStatusCode.BadRequest, withoutKey.status)

        val jobId = client.createJob()
        val handed = assertNotNull(client.workerNext())
        assertEquals(metaKey, handed.metaApiKey)
        assertTrue(metaKey !in handed.toString(), "the job's toString hides the key")

        // A question drops the key; the answer has to bring it again.
        client.workerStatus(jobId, WorkerStatusUpdate(JobStatus.NEEDS_INFO, question = "How many glasses?"))
        val answerWithoutKey = client.post(ForgeRoutes.answer(jobId)) {
            bearerAuth(tokens.client)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest("Eight"))
        }
        assertEquals(HttpStatusCode.BadRequest, answerWithoutKey.status)
        client.post(ForgeRoutes.answer(jobId)) {
            bearerAuth(tokens.client)
            header(ForgeRoutes.META_KEY_HEADER, metaKey)
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest("Eight"))
        }
        assertEquals(metaKey, assertNotNull(client.workerNext()).metaApiKey)
        client.workerArtifact(jobId)

        val onDisk = dataDir.walkTopDown().filter { it.isFile }.map { it.readText(Charsets.ISO_8859_1) }.toList()
        assertTrue(onDisk.isNotEmpty() && onDisk.none { metaKey in it }, "no file of the gateway holds the key")
    }

    @Test
    fun `after a restart a queued job without its key fails with a sentence instead of reaching the worker`() = testApplication {
        val dataDir = Files.createTempDirectory("forge-key-restart").toFile()
        val client = start(dataDir)
        val jobId = client.createJob()

        val reloaded = JobStore(dataDir)
        kotlinx.coroutines.runBlocking {
            assertNull(reloaded.awaitNext(0))
            val job = assertNotNull(reloaded.get(jobId))
            assertEquals(JobStatus.FAILED, job.status)
            assertEquals(JobStore.NO_KEY_MESSAGE, job.message)
        }
    }
}
