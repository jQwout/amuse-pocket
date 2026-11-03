package dev.amuse.forge.gateway

import dev.amuse.forge.contract.Artifact
import dev.amuse.forge.contract.CreateJobRequest
import dev.amuse.forge.contract.Exchange
import dev.amuse.forge.contract.ForgeRoutes
import dev.amuse.forge.contract.JobStatus
import dev.amuse.forge.contract.JobView
import dev.amuse.forge.contract.WorkerArtifactUpload
import dev.amuse.forge.contract.WorkerJob
import dev.amuse.forge.contract.WorkerStatusUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * One job on disk and in memory. The gateway keeps the full record; [JobView] is the slice the app
 * sees and [WorkerJob] the slice the worker gets.
 */
@Serializable
data class Job(
    val jobId: String,
    val agentId: String,
    val agentName: String,
    val agentDescription: String,
    val request: String,
    val previousJobId: String? = null,
    val status: JobStatus,
    val message: String? = null,
    val question: String? = null,
    val exchanges: List<Exchange> = emptyList(),
    /** Fixed when the worker takes the job, so the artifact's version is known up front. */
    val version: Int,
    val artifact: Artifact? = null,
    val entryPoint: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** When the worker last touched the job; a stale ACCEPTED/WORKING job goes back to the queue. */
    val leasedAt: Long? = null,
) {
    fun toView() = JobView(
        jobId = jobId,
        agentId = agentId,
        status = status,
        message = message,
        question = question,
        artifact = artifact,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** What an artifact upload came to; see [JobStore.attachArtifact]. */
sealed interface AttachResult {
    data class Done(val job: Job) : AttachResult
    data class NotHeld(val job: Job) : AttachResult
    data object NotFound : AttachResult
}

/**
 * The queue and the bundle store: a map guarded by one mutex, mirrored to `dataDir/jobs/<id>/`
 * (`job.json`, `bundle.ktx`, `source.kt`) so a restart of the gateway loses nothing. Small enough
 * for a demo and for a single Fly machine; a database would be the next step, not a different shape.
 */
class JobStore(
    private val dataDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val leaseTimeoutMillis: Long = 20 * 60 * 1000L,
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val jobs = LinkedHashMap<String, Job>()

    init {
        val root = File(dataDir, "jobs")
        root.mkdirs()
        root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { dir ->
            val file = File(dir, "job.json")
            if (file.isFile) {
                runCatching { json.decodeFromString<Job>(file.readText()) }
                    .onSuccess { jobs[it.jobId] = it }
            }
        }
    }

    suspend fun create(request: CreateJobRequest): Job = mutex.withLock {
        val t = now()
        // An update may only build on a screen of the same agent: the previous source is handed to the worker.
        val previous = request.previousJobId?.let { jobs[it] }?.takeIf { it.agentId == request.agentId }
        val job = Job(
            jobId = newId(),
            agentId = request.agentId,
            agentName = request.agentName,
            agentDescription = request.agentDescription,
            request = request.request,
            previousJobId = previous?.jobId,
            status = JobStatus.QUEUED,
            version = nextVersionLocked(request.agentId),
            createdAt = t,
            updatedAt = t,
        )
        jobs[job.jobId] = job
        persist(job)
        job
    }

    suspend fun get(jobId: String): Job? = mutex.withLock { jobs[jobId] }

    suspend fun all(): List<Job> = mutex.withLock { jobs.values.toList() }

    /** The person answered: the question becomes an exchange and the job queues again. */
    suspend fun answer(jobId: String, text: String): Job? = mutex.withLock {
        val job = jobs[jobId] ?: return@withLock null
        if (job.status != JobStatus.NEEDS_INFO) return@withLock job
        val updated = job.copy(
            status = JobStatus.QUEUED,
            exchanges = job.exchanges + Exchange(question = job.question ?: "", answer = text),
            question = null,
            message = null,
            updatedAt = now(),
            leasedAt = null,
        )
        jobs[jobId] = updated
        persist(updated)
        updated
    }

    /**
     * Long poll for the worker: the oldest queued job, or null once [waitMillis] has passed.
     * Handing the job out marks it ACCEPTED so a second worker cannot take the same one.
     */
    suspend fun awaitNext(waitMillis: Long): WorkerJob? {
        val deadline = now() + waitMillis
        while (true) {
            takeNext()?.let { return it }
            if (now() >= deadline) return null
            delay(POLL_STEP_MILLIS)
        }
    }

    private suspend fun takeNext(): WorkerJob? = mutex.withLock {
        val t = now()
        // A worker that died mid-job must not hold it forever.
        jobs.values.filter { it.leasedAt != null && !it.status.isTerminal && t - it.leasedAt!! > leaseTimeoutMillis }
            .forEach { stale ->
                val back = stale.copy(status = JobStatus.QUEUED, leasedAt = null, updatedAt = t)
                jobs[stale.jobId] = back
                persist(back)
            }
        val job = jobs.values.firstOrNull { it.status == JobStatus.QUEUED } ?: return@withLock null
        val leased = job.copy(status = JobStatus.ACCEPTED, leasedAt = t, updatedAt = t)
        jobs[job.jobId] = leased
        persist(leased)
        val previous = job.previousJobId?.let { jobs[it] }
        WorkerJob(
            jobId = job.jobId,
            agentId = job.agentId,
            agentName = job.agentName,
            agentDescription = job.agentDescription,
            request = job.request,
            previousSource = previous?.let { readSource(it.jobId) },
            previousEntryPoint = previous?.entryPoint,
            exchanges = job.exchanges,
            version = job.version,
        )
    }

    suspend fun updateStatus(jobId: String, update: WorkerStatusUpdate): Job? = mutex.withLock {
        val job = jobs[jobId] ?: return@withLock null
        if (job.status.isTerminal) return@withLock job
        val t = now()
        val updated = job.copy(
            status = update.status,
            message = update.message ?: if (update.status == JobStatus.NEEDS_INFO) null else job.message,
            question = if (update.status == JobStatus.NEEDS_INFO) update.question else null,
            updatedAt = t,
            leasedAt = if (update.status == JobStatus.NEEDS_INFO || update.status.isTerminal) null else t,
        )
        jobs[jobId] = updated
        persist(updated)
        updated
    }

    /**
     * The worker delivered: store the bytes and the source, mark DONE with a verifiable artifact.
     * Only a job a worker holds (ACCEPTED or WORKING) takes an artifact: a queued job was never
     * handed out, and a finished one keeps the bundle the app may already have verified.
     */
    suspend fun attachArtifact(jobId: String, upload: WorkerArtifactUpload): AttachResult = mutex.withLock {
        val job = jobs[jobId] ?: return@withLock AttachResult.NotFound
        if (job.status != JobStatus.ACCEPTED && job.status != JobStatus.WORKING) return@withLock AttachResult.NotHeld(job)
        val bytes = Base64.getDecoder().decode(upload.bundleBase64)
        val dir = jobDir(jobId).also { it.mkdirs() }
        File(dir, BUNDLE_FILE).writeBytes(bytes)
        File(dir, SOURCE_FILE).writeText(upload.source)
        val t = now()
        val updated = job.copy(
            status = JobStatus.DONE,
            message = null,
            question = null,
            entryPoint = upload.entryPoint,
            artifact = Artifact(
                url = ForgeRoutes.bundle(jobId),
                sha256 = sha256Hex(bytes),
                entryPoint = upload.entryPoint,
                version = job.version,
                sizeBytes = bytes.size.toLong(),
            ),
            updatedAt = t,
            leasedAt = null,
        )
        jobs[jobId] = updated
        persist(updated)
        AttachResult.Done(updated)
    }

    suspend fun bundleBytes(jobId: String): ByteArray? = mutex.withLock {
        val file = File(jobDir(jobId), BUNDLE_FILE)
        if (file.isFile) file.readBytes() else null
    }

    private fun readSource(jobId: String): String? =
        File(jobDir(jobId), SOURCE_FILE).takeIf { it.isFile }?.readText()

    /** 1 for an agent's first screen; a failed or abandoned job does not use a number up. */
    private fun nextVersionLocked(agentId: String): Int =
        (jobs.values.filter { it.agentId == agentId && it.status == JobStatus.DONE }.maxOfOrNull { it.version } ?: 0) + 1

    private fun jobDir(jobId: String) = File(File(dataDir, "jobs"), jobId)

    private fun persist(job: Job) {
        val dir = jobDir(job.jobId).also { it.mkdirs() }
        val tmp = File(dir, "job.json.tmp")
        tmp.writeText(json.encodeToString(job))
        val target = File(dir, "job.json")
        if (!tmp.renameTo(target)) {
            target.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun newId(): String = "job_" + UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        const val BUNDLE_FILE = "bundle.ktx"
        const val SOURCE_FILE = "source.kt"
        private const val POLL_STEP_MILLIS = 500L

        private val ID_RULE = Regex("[A-Za-z0-9_-]{1,64}")

        fun isValidId(id: String): Boolean = ID_RULE.matches(id)

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
