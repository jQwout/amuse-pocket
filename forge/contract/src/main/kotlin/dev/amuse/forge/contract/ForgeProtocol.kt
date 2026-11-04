package dev.amuse.forge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The forge protocol, as the app, the gateway and the worker all see it.
 *
 * A job is one request for one screen of one agent. It moves through the statuses below once;
 * `NEEDS_INFO` sends it back to the queue after the person answers, and `DONE` carries the
 * artifact the app downloads. Status names travel as lowercase strings so the app's mirror and
 * any curl session read them without a decoder table.
 */
@Serializable
enum class JobStatus {
    @SerialName("queued") QUEUED,
    @SerialName("accepted") ACCEPTED,
    @SerialName("needs_info") NEEDS_INFO,
    @SerialName("working") WORKING,
    @SerialName("done") DONE,
    @SerialName("failed") FAILED;

    val isTerminal: Boolean get() = this == DONE || this == FAILED
}

/** `POST /v1/jobs` - the app asks for a screen. */
@Serializable
data class CreateJobRequest(
    val agentId: String,
    val agentName: String,
    val agentDescription: String,
    /** What the person typed: the screen they want, in their own words. */
    val request: String,
    /** The job whose screen this one should change, for "Update" - its source is handed to the agent. */
    val previousJobId: String? = null,
)

@Serializable
data class CreateJobResponse(val jobId: String)

/** `POST /v1/jobs/{id}/answer` - the person answers the agent's question. */
@Serializable
data class AnswerRequest(val text: String)

/** Where the finished bundle is and what to render from it. */
@Serializable
data class Artifact(
    /** Absolute or gateway-relative URL of the `.ktx`. */
    val url: String,
    /** Hex SHA-256 of the bundle bytes; the app refuses a download that does not match. */
    val sha256: String,
    /** Name of the `@KetoyEntryPoint` function to render. */
    val entryPoint: String,
    /** 1 for the agent's first screen, +1 per update. */
    val version: Int,
    val sizeBytes: Long,
)

/** `GET /v1/jobs/{id}` - everything the app shows about a job. */
@Serializable
data class JobView(
    val jobId: String,
    val agentId: String,
    val status: JobStatus,
    /** Progress or failure text from the worker, for the person. */
    val message: String? = null,
    /** Set with [JobStatus.NEEDS_INFO]: what the agent needs to know. */
    val question: String? = null,
    val artifact: Artifact? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** `GET /v1/worker/next` - one job handed to the worker, with all the context the agent needs. */
@Serializable
data class WorkerJob(
    val jobId: String,
    val agentId: String,
    val agentName: String,
    val agentDescription: String,
    val request: String,
    /** The Kotlin source of the screen being updated, when this job is an "Update". */
    val previousSource: String? = null,
    val previousEntryPoint: String? = null,
    /** Question/answer pairs so far, oldest first; empty on a first attempt. */
    val exchanges: List<Exchange> = emptyList(),
    /** The version the artifact will get when this job succeeds. */
    val version: Int,
    /**
     * The person's own Meta key, passed through from the app for this job only: the worker has no key
     * of its own, and the gateway keeps it in memory while the job is queued or held, never on disk.
     */
    val metaApiKey: String = "",
) {
    override fun toString(): String =
        "WorkerJob(jobId=$jobId, agentId=$agentId, version=$version, exchanges=${exchanges.size}, metaApiKey=<hidden>)"
}

@Serializable
data class Exchange(val question: String, val answer: String)

/** `POST /v1/worker/jobs/{id}/status` - the worker reports progress, a question or a failure. */
@Serializable
data class WorkerStatusUpdate(
    val status: JobStatus,
    val message: String? = null,
    val question: String? = null,
)

/** `POST /v1/worker/jobs/{id}/artifact` - the worker delivers the bundle. Small files, so base64 in JSON. */
@Serializable
data class WorkerArtifactUpload(
    val entryPoint: String,
    /** Kotlin source of the screen, kept so a later "Update" can hand it back to the agent. */
    val source: String,
    val bundleBase64: String,
)

object ForgeRoutes {
    const val JOBS = "/v1/jobs"
    const val BUNDLES = "/v1/bundles"
    const val WORKER_NEXT = "/v1/worker/next"
    const val WORKER_JOBS = "/v1/worker/jobs"
    const val HEALTH = "/health"

    /**
     * Header on `POST /v1/jobs` and `POST /v1/jobs/{id}/answer`: the person's Meta key, which pays for
     * the job's model calls. A header, not a body field, so it never lands in the persisted job.
     */
    const val META_KEY_HEADER = "X-Meta-Api-Key"

    fun job(id: String) = "$JOBS/$id"
    fun answer(id: String) = "$JOBS/$id/answer"
    fun bundle(id: String) = "$BUNDLES/$id.ktx"
    fun workerStatus(id: String) = "$WORKER_JOBS/$id/status"
    fun workerArtifact(id: String) = "$WORKER_JOBS/$id/artifact"
}
