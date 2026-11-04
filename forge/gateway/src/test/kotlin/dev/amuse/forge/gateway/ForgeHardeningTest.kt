package dev.amuse.forge.gateway

import dev.amuse.forge.contract.AnswerRequest
import dev.amuse.forge.contract.CreateJobRequest
import dev.amuse.forge.contract.CreateJobResponse
import dev.amuse.forge.contract.ForgeRoutes
import dev.amuse.forge.contract.JobStatus
import dev.amuse.forge.contract.WorkerArtifactUpload
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
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/** The abuse brakes on the forge side: sizes, rates, and a worker that cannot rewrite history. */
class ForgeHardeningTest {

    private val tokens = ForgeTokens(client = "client-secret", worker = "worker-secret")

    private fun ApplicationTestBuilder.start(limits: ForgeLimits = ForgeLimits()): HttpClient {
        val store = JobStore(Files.createTempDirectory("forge-hardening").toFile())
        application { forgeModule(store, tokens, longPollMaxSeconds = 1, forgeLimits = limits) }
        return createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private fun job(request: String = "a water tracker", agentId: String = "agent_1", agentName: String = "Hydration coach") =
        CreateJobRequest(agentId, agentName, "keeps me drinking", request)

    private suspend fun HttpClient.create(body: CreateJobRequest = job()): HttpResponse =
        post(ForgeRoutes.JOBS) {
            bearerAuth(tokens.client)
            header(ForgeRoutes.META_KEY_HEADER, "LLM_test_key")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.createdId(): String = create().body<CreateJobResponse>().jobId

    private suspend fun HttpClient.take() = get("${ForgeRoutes.WORKER_NEXT}?wait=0") { bearerAuth(tokens.worker) }

    private suspend fun HttpClient.upload(jobId: String): HttpResponse =
        post(ForgeRoutes.workerArtifact(jobId)) {
            bearerAuth(tokens.worker)
            contentType(ContentType.Application.Json)
            setBody(WorkerArtifactUpload("Screen", "fun Screen() {}", Base64.getEncoder().encodeToString("bytes".toByteArray())))
        }

    @Test
    fun `job fields are bounded`() = testApplication {
        val client = start()
        assertEquals(HttpStatusCode.BadRequest, client.create(job(request = "x".repeat(ForgeLimits().maxRequestChars + 1))).status)
        assertEquals(HttpStatusCode.BadRequest, client.create(job(agentName = "x".repeat(61))).status)
        assertEquals(HttpStatusCode.BadRequest, client.create(job(agentId = "../../etc")).status)
        assertEquals(HttpStatusCode.Accepted, client.create().status)
    }

    @Test
    fun `an answer is bounded too`() = testApplication {
        val client = start()
        val id = client.createdId()
        client.take()
        client.post(ForgeRoutes.workerStatus(id)) {
            bearerAuth(tokens.worker)
            contentType(ContentType.Application.Json)
            setBody(WorkerStatusUpdate(JobStatus.NEEDS_INFO, question = "How many?"))
        }
        val tooLong = client.post(ForgeRoutes.answer(id)) {
            bearerAuth(tokens.client)
            header(ForgeRoutes.META_KEY_HEADER, "LLM_test_key")
            contentType(ContentType.Application.Json)
            setBody(AnswerRequest("x".repeat(ForgeLimits().maxAnswerChars + 1)))
        }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
    }

    @Test
    fun `client bodies over the limit are refused`() = testApplication {
        val client = start(ForgeLimits(maxClientBodyBytes = 200))
        assertEquals(HttpStatusCode.PayloadTooLarge, client.create(job(request = "x".repeat(500))).status)
    }

    @Test
    fun `artifact uploads over the limit are refused`() = testApplication {
        val client = start(ForgeLimits(maxWorkerBodyBytes = 50))
        val id = client.createdId()
        client.take()
        assertEquals(HttpStatusCode.PayloadTooLarge, client.upload(id).status)
    }

    @Test
    fun `queueing jobs is rate limited, since every job spends model calls`() = testApplication {
        val client = start(ForgeLimits(jobsPerHour = 2))
        client.create()
        client.create()
        assertEquals(HttpStatusCode.TooManyRequests, client.create().status)
    }

    @Test
    fun `an artifact only lands on a job the worker holds`() = testApplication {
        val client = start()
        val queued = client.createdId()
        assertEquals(HttpStatusCode.Conflict, client.upload(queued).status, "nobody took this job yet")

        client.take()
        assertEquals(HttpStatusCode.OK, client.upload(queued).status)
        assertEquals(HttpStatusCode.Conflict, client.upload(queued).status, "a done job keeps its bundle")
    }

    @Test
    fun `tokens are compared in full`() = testApplication {
        val client = start()
        val prefix = client.get(ForgeRoutes.job("x")) { bearerAuth("client-secre") }
        val longer = client.get(ForgeRoutes.job("x")) { bearerAuth("client-secret-and-more") }
        assertEquals(HttpStatusCode.Unauthorized, prefix.status)
        assertEquals(HttpStatusCode.Unauthorized, longer.status)
    }
}
