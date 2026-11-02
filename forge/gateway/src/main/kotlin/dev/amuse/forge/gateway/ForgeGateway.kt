package dev.amuse.forge.gateway

import dev.amuse.forge.contract.AnswerRequest
import dev.amuse.forge.contract.CreateJobRequest
import dev.amuse.forge.contract.CreateJobResponse
import dev.amuse.forge.contract.ForgeRoutes
import dev.amuse.forge.contract.JobStatus
import dev.amuse.forge.contract.WorkerArtifactUpload
import dev.amuse.forge.contract.WorkerStatusUpdate
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Tokens the two sides present. Either may be empty, which disables that side (nothing gets in). */
data class ForgeTokens(val client: String, val worker: String)

const val AUTH_CLIENT = "client"
const val AUTH_WORKER = "worker"

/**
 * The gateway's whole surface. The app side is authenticated with the client token, the worker
 * side with the worker token; both are plain bearer tokens because the only secret worth
 * protecting here is the right to queue work and to fetch what the worker built.
 */
fun Application.forgeModule(store: JobStore, tokens: ForgeTokens, longPollMaxSeconds: Long = 25) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }
    install(CallLogging)
    install(StatusPages) {
        exception<SerializationException> { call, cause ->
            call.respondText("Bad request: ${cause.message}", status = HttpStatusCode.BadRequest)
        }
        exception<IllegalArgumentException> { call, cause ->
            call.respondText("Bad request: ${cause.message}", status = HttpStatusCode.BadRequest)
        }
    }
    authentication {
        bearer(AUTH_CLIENT) {
            authenticate { credential ->
                if (tokens.client.isNotBlank() && credential.token == tokens.client) UserIdPrincipal("client") else null
            }
        }
        bearer(AUTH_WORKER) {
            authenticate { credential ->
                if (tokens.worker.isNotBlank() && credential.token == tokens.worker) UserIdPrincipal("worker") else null
            }
        }
    }

    routing {
        get(ForgeRoutes.HEALTH) { call.respondText("ok") }

        authenticate(AUTH_CLIENT) {
            post(ForgeRoutes.JOBS) {
                val request = call.receive<CreateJobRequest>()
                require(request.agentId.isNotBlank()) { "agentId is required" }
                require(request.request.isNotBlank()) { "request is required" }
                val job = store.create(request)
                call.respond(HttpStatusCode.Accepted, CreateJobResponse(job.jobId))
            }
            get("${ForgeRoutes.JOBS}/{id}") {
                val job = store.get(call.parameters["id"].orEmpty())
                if (job == null) call.respondText("No such job", status = HttpStatusCode.NotFound)
                else call.respond(job.toView())
            }
            post("${ForgeRoutes.JOBS}/{id}/answer") {
                val id = call.parameters["id"].orEmpty()
                val answer = call.receive<AnswerRequest>()
                require(answer.text.isNotBlank()) { "text is required" }
                val job = store.answer(id, answer.text)
                if (job == null) call.respondText("No such job", status = HttpStatusCode.NotFound)
                else call.respond(job.toView())
            }
            get("${ForgeRoutes.BUNDLES}/{file}") {
                val id = call.parameters["file"].orEmpty().removeSuffix(".ktx")
                // The job is looked up before anything touches the disk, and only our own id shape reaches a path.
                val job = id.takeIf { JobStore.isValidId(it) }?.let { store.get(it) }
                val bytes = if (job?.artifact != null) store.bundleBytes(id) else null
                if (job?.artifact == null || bytes == null) {
                    call.respondText("No bundle for this job", status = HttpStatusCode.NotFound)
                } else {
                    val etag = "\"${job.artifact.sha256}\""
                    if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
                        call.respond(HttpStatusCode.NotModified)
                    } else {
                        call.response.header(HttpHeaders.ETag, etag)
                        call.respondBytes(bytes, ContentType.Application.OctetStream)
                    }
                }
            }
        }

        authenticate(AUTH_WORKER) {
            get(ForgeRoutes.WORKER_NEXT) {
                val wait = (call.request.queryParameters["wait"]?.toLongOrNull() ?: longPollMaxSeconds)
                    .coerceIn(0, longPollMaxSeconds)
                val job = store.awaitNext(wait * 1000)
                if (job == null) call.respond(HttpStatusCode.NoContent) else call.respond(job)
            }
            post("${ForgeRoutes.WORKER_JOBS}/{id}/status") {
                val id = call.parameters["id"].orEmpty()
                val update = call.receive<WorkerStatusUpdate>()
                require(update.status != JobStatus.DONE) { "done is reported through the artifact upload" }
                require(update.status != JobStatus.NEEDS_INFO || !update.question.isNullOrBlank()) {
                    "needs_info requires a question"
                }
                val job = store.updateStatus(id, update)
                if (job == null) call.respondText("No such job", status = HttpStatusCode.NotFound)
                else call.respond(job.toView())
            }
            post("${ForgeRoutes.WORKER_JOBS}/{id}/artifact") {
                val id = call.parameters["id"].orEmpty()
                val upload = call.receive<WorkerArtifactUpload>()
                require(upload.entryPoint.isNotBlank()) { "entryPoint is required" }
                require(upload.bundleBase64.isNotBlank()) { "bundleBase64 is required" }
                val job = store.attachArtifact(id, upload)
                if (job == null) call.respondText("No such job", status = HttpStatusCode.NotFound)
                else call.respond(job.toView())
            }
        }
    }
}
