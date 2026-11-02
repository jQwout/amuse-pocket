package dev.amuse.forge.worker

import dev.amuse.forge.contract.JobStatus
import dev.amuse.forge.contract.WorkerArtifactUpload
import dev.amuse.forge.contract.WorkerJob
import dev.amuse.forge.contract.WorkerStatusUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.util.Base64

/**
 * The worker's main loop: take a job from the gateway, run the coding agent, report the result.
 *
 * Nothing that goes wrong in one job or one network call may end the process: polling errors back
 * off and retry, a crashing agent becomes a `failed` status.
 */
class Worker(
    private val gateway: GatewayClient,
    private val agent: suspend (WorkerJob, suspend (String) -> Unit) -> ForgeOutcome,
    private val pollWaitSeconds: Int = 25,
    private val backoffStartMs: Long = 1_000,
    private val backoffMaxMs: Long = 30_000,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val log = LoggerFactory.getLogger(Worker::class.java)

    /** Runs until cancelled, or until one job has been processed when [once] is set. */
    suspend fun run(once: Boolean = false) {
        var backoff = backoffStartMs
        while (true) {
            val job = try {
                gateway.nextJob(pollWaitSeconds).also { backoff = backoffStartMs }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("poll failed, retrying in {} ms: {}", backoff, e.message)
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(backoffMaxMs)
                continue
            }
            if (job == null) continue
            process(job)
            if (once) return
        }
    }

    suspend fun process(job: WorkerJob) {
        log.info("job {} accepted: agent='{}' version={} update={} exchanges={}", job.jobId, job.agentName, job.version, job.previousSource != null, job.exchanges.size)
        report(job, WorkerStatusUpdate(JobStatus.ACCEPTED, "The forge picked up your request"))
        report(job, WorkerStatusUpdate(JobStatus.WORKING, "Designing the screen"))

        val outcome = try {
            agent(job) { text -> report(job, WorkerStatusUpdate(JobStatus.WORKING, text)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("job {} crashed", job.jobId, e)
            ForgeOutcome.Failed("The forge hit an internal error: ${e::class.simpleName}")
        }

        when (outcome) {
            is ForgeOutcome.Done -> {
                log.info("job {} built {} ({} bytes), uploading", job.jobId, outcome.entryPoint, outcome.bundleBytes.size)
                try {
                    gateway.uploadArtifact(
                        job.jobId,
                        WorkerArtifactUpload(
                            entryPoint = outcome.entryPoint,
                            source = outcome.source,
                            bundleBase64 = Base64.getEncoder().encodeToString(outcome.bundleBytes),
                        ),
                    )
                    log.info("job {} done", job.jobId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("job {} upload failed: {}", job.jobId, e.message)
                    report(job, WorkerStatusUpdate(JobStatus.FAILED, "The screen was built but could not be delivered. Please try again."))
                }
            }
            is ForgeOutcome.NeedsInfo -> {
                log.info("job {} needs info: {}", job.jobId, outcome.question)
                report(job, WorkerStatusUpdate(JobStatus.NEEDS_INFO, question = outcome.question))
            }
            is ForgeOutcome.Failed -> {
                log.warn("job {} failed: {}", job.jobId, outcome.message.lineSequence().first())
                report(job, WorkerStatusUpdate(JobStatus.FAILED, outcome.message.take(MAX_MESSAGE_CHARS)))
            }
        }
    }

    /** Status calls are best effort: a missed progress message must not abort a build that is going well. */
    private suspend fun report(job: WorkerJob, update: WorkerStatusUpdate) {
        try {
            gateway.updateStatus(job.jobId, update)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("job {} status {} not delivered: {}", job.jobId, update.status, e.message)
        }
    }

    private companion object {
        const val MAX_MESSAGE_CHARS = 1_500
    }
}
