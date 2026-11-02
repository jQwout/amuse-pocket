package dev.amuse.forge.gateway

import dev.amuse.forge.contract.AnswerRequest
import dev.amuse.forge.contract.CreateJobRequest
import dev.amuse.forge.contract.CreateJobResponse
import dev.amuse.forge.contract.ForgeRoutes
import dev.amuse.forge.contract.JobStatus
import dev.amuse.forge.contract.WorkerArtifactUpload
import dev.amuse.forge.contract.WorkerStatusUpdate
import dev.amuse.forge.gateway.config.ConfigStore
import dev.amuse.forge.gateway.config.configRead
import dev.amuse.forge.gateway.config.configWrite
import dev.amuse.forge.gateway.market.MarketLimits
import dev.amuse.forge.gateway.market.ai.MarketAi
import dev.amuse.forge.gateway.market.ai.marketAiPublicApi
import dev.amuse.forge.gateway.market.MarketStore
import dev.amuse.forge.gateway.market.marketApi
import dev.amuse.forge.gateway.market.marketModerationApi
import dev.amuse.forge.gateway.market.marketPublicApi
import dev.amuse.forge.gateway.market.marketSharePage
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

/**
 * Tokens the sides present. Any may be empty, which disables that side (nothing gets in). [admin]
 * is the owner's: it writes the remote config.
 */
data class ForgeTokens(val client: String, val worker: String, val admin: String = "")

const val AUTH_CLIENT = "client"
const val AUTH_WORKER = "worker"
const val AUTH_ADMIN = "admin"
private const val HOUR_MILLIS = 60 * 60 * 1000L

/**
 * The gateway's whole surface. The app side is authenticated with the client token, the worker
 * side with the worker token; both are plain bearer tokens because the only secret worth
 * protecting here is the right to queue work and to fetch what the worker built.
 */
fun Application.forgeModule(
    store: JobStore,
    tokens: ForgeTokens,
    longPollMaxSeconds: Long = 25,
    /** Null leaves the market out (the forge-only tests); see `docs/market-api.md`. */
    market: MarketStore? = null,
    marketLimits: MarketLimits = MarketLimits(),
    forgeLimits: ForgeLimits = ForgeLimits(),
    /** Null leaves remote config out; see `docs/remote-config.md`. */
    config: ConfigStore? = null,
    /** Null when there is no Meta key: the market works, without review, covers and smart search. */
    marketAi: MarketAi? = null,
    /** Absolute address of this gateway, for link previews (`og:image`); null leaves them text-only. */
    publicBaseUrl: String? = null,
) {
    val jobLimiter = RateLimiter(forgeLimits.jobsPerHour, HOUR_MILLIS)
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
                if (secretMatches(tokens.client, credential.token)) UserIdPrincipal("client") else null
            }
        }
        bearer(AUTH_WORKER) {
            authenticate { credential ->
                if (secretMatches(tokens.worker, credential.token)) UserIdPrincipal("worker") else null
            }
        }
        bearer(AUTH_ADMIN) {
            authenticate { credential ->
                if (secretMatches(tokens.admin, credential.token)) UserIdPrincipal("admin") else null
            }
        }
    }

    routing {
        get(ForgeRoutes.HEALTH) { call.respondText("ok") }

        if (config != null) {
            configRead(config)
            authenticate(AUTH_ADMIN) { configWrite(config, forgeLimits.maxClientBodyBytes) }
        }

        if (market != null) {
            marketSharePage(market, publicBaseUrl)
            marketPublicApi(market, marketLimits, marketAi)
            marketAiPublicApi(market, marketAi, marketLimits)
            authenticate(AUTH_CLIENT) { marketApi(market, marketLimits, marketAi) }
            authenticate(AUTH_ADMIN) { marketModerationApi(market) }
        }

        authenticate(AUTH_CLIENT) {
            post(ForgeRoutes.JOBS) {
                if (!bodyFits(forgeLimits.maxClientBodyBytes)) return@post
                if (!allowedBy(jobLimiter, forgeLimits.trustFlyClientIp)) return@post
                val request = call.receive<CreateJobRequest>()
                val issues = forgeLimits.issuesOf(request)
                if (issues.isNotEmpty()) {
                    return@post call.respondText("Bad request: ${issues.joinToString("; ")}", status = HttpStatusCode.BadRequest)
                }
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
                if (!bodyFits(forgeLimits.maxClientBodyBytes)) return@post
                val answer = call.receive<AnswerRequest>()
                require(answer.text.isNotBlank()) { "text is required" }
                require(answer.text.length <= forgeLimits.maxAnswerChars) { "text is longer than ${forgeLimits.maxAnswerChars} characters" }
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
                if (!bodyFits(forgeLimits.maxClientBodyBytes)) return@post
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
                if (!bodyFits(forgeLimits.maxWorkerBodyBytes)) return@post
                val upload = call.receive<WorkerArtifactUpload>()
                require(upload.entryPoint.isNotBlank()) { "entryPoint is required" }
                require(upload.bundleBase64.isNotBlank()) { "bundleBase64 is required" }
                when (val result = store.attachArtifact(id, upload)) {
                    is AttachResult.Done -> call.respond(result.job.toView())
                    is AttachResult.NotHeld -> call.respondText(
                        "Job is ${result.job.status.name.lowercase()}, not held by a worker",
                        status = HttpStatusCode.Conflict,
                    )
                    AttachResult.NotFound -> call.respondText("No such job", status = HttpStatusCode.NotFound)
                }
            }
        }
    }
}
