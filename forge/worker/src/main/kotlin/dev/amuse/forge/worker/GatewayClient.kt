package dev.amuse.forge.worker

import dev.amuse.forge.contract.ForgeRoutes
import dev.amuse.forge.contract.WorkerArtifactUpload
import dev.amuse.forge.contract.WorkerJob
import dev.amuse.forge.contract.WorkerStatusUpdate
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json

class GatewayException(val status: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The worker's side of the gateway protocol (see `ForgeProtocol.kt`).
 *
 * Methods throw [GatewayException] on anything unexpected; the poll loop in [Worker] is what turns
 * that into "log and retry with backoff", so a gateway restart never kills the worker.
 */
class GatewayClient(
    baseUrl: String,
    private val workerToken: String,
    engine: HttpClientEngine = CIO.create(),
    /** Must stay longer than the long-poll wait. */
    requestTimeoutMs: Long = 60_000,
    /** Pauses between upload retries; overridden in tests. */
    private val uploadRetryDelaysMs: List<Long> = listOf(1_000, 3_000, 8_000),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : AutoCloseable {
    private val base = baseUrl.trimEnd('/')

    private val http = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = requestTimeoutMs
            this.requestTimeoutMillis = requestTimeoutMs
        }
    }

    /** Long-polls for the next queued job; null when the gateway had none within [waitSeconds] (HTTP 204). */
    suspend fun nextJob(waitSeconds: Int = 25): WorkerJob? {
        val response = call("poll for the next job") {
            http.get("$base${ForgeRoutes.WORKER_NEXT}") {
                parameter("wait", waitSeconds)
                header(HttpHeaders.Authorization, "Bearer $workerToken")
            }
        }
        return when (response.status) {
            HttpStatusCode.NoContent -> null
            HttpStatusCode.OK -> try {
                response.body<WorkerJob>()
            } catch (e: Exception) {
                throw GatewayException(200, "The gateway sent a job the worker cannot read: ${e.message}", e)
            }
            else -> throw failure("poll for the next job", response)
        }
    }

    suspend fun updateStatus(jobId: String, update: WorkerStatusUpdate) {
        post(ForgeRoutes.workerStatus(jobId), update, "report status ${update.status} for $jobId")
    }

    /** Uploads the bundle; retried a few times because losing a finished build to a network blip is expensive. */
    suspend fun uploadArtifact(jobId: String, upload: WorkerArtifactUpload) {
        var attempt = 0
        while (true) {
            try {
                post(ForgeRoutes.workerArtifact(jobId), upload, "upload the artifact for $jobId")
                return
            } catch (e: GatewayException) {
                val status = e.status
                val permanent = status != null && status in 400..499 && status != 408 && status != 429
                if (permanent || attempt >= uploadRetryDelaysMs.size) throw e
                sleep(uploadRetryDelaysMs[attempt++])
            }
        }
    }

    private suspend inline fun <reified T> post(route: String, payload: T, what: String) {
        val response = call(what) {
            http.post("$base$route") {
                header(HttpHeaders.Authorization, "Bearer $workerToken")
                contentType(ContentType.Application.Json)
                setBody(payload)
            }
        }
        if (response.status.value !in 200..299) throw failure(what, response)
    }

    private suspend fun call(what: String, block: suspend () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw GatewayException(null, "Could not $what: ${e::class.simpleName}: ${e.message}", e)
        }

    private suspend fun failure(what: String, response: HttpResponse): GatewayException {
        val body = runCatching { response.bodyAsText() }.getOrDefault("").take(300)
        return GatewayException(response.status.value, "Could not $what: HTTP ${response.status.value} $body".trim())
    }

    override fun close() = http.close()
}
