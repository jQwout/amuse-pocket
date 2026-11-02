package dev.amuse.forge.gateway

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

// @trace market-ai-8f3b21

/** Work that must not hold up an HTTP answer (model calls after a publish). */
fun interface TaskRunner {
    fun submit(name: String, task: suspend () -> Unit)
}

/**
 * Runs tasks on its own scope, at most [parallelism] at a time, so a burst of publishes turns into
 * a short queue rather than a burst of paid calls. A failing task is logged and forgotten; whatever
 * it left undone is picked up again on the next start.
 */
class CoroutineTaskRunner(
    private val parallelism: Int = 2,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : TaskRunner {
    private val permits = Semaphore(parallelism)
    private val log = LoggerFactory.getLogger(CoroutineTaskRunner::class.java)

    override fun submit(name: String, task: suspend () -> Unit) {
        scope.launch {
            permits.withPermit {
                runCatching { task() }.onFailure { log.warn("Background task '{}' failed: {}", name, it.message) }
            }
        }
    }
}
