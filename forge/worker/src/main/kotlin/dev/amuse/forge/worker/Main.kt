package dev.amuse.forge.worker

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/**
 * Entry point. Configuration comes from the environment:
 *
 *   FORGE_GATEWAY_URL, FORGE_WORKER_TOKEN   required
 *   (no Meta key: each job carries the key of the person who asked for it, see [WorkerJob.metaApiKey])
 *   FORGE_MODEL (muse-spark-1.3), META_BASE_URL (https://api.meta.ai/v1),
 *   FORGE_DIR (the forge directory, found from the working directory),
 *   FORGE_REASONING_EFFORT (medium; "off" omits the field)   optional
 *
 * `--once` processes a single job and exits (demos, tests).
 * `--self-test` builds a fixed sample screen through the same tools, with no model or gateway
 * (needs only FORGE_DIR if started outside the forge directory).
 */
fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("forge-worker")
    if ("--self-test" in args) {
        val forgeDir = System.getenv("FORGE_DIR")?.takeIf { it.isNotBlank() }?.let { java.nio.file.Paths.get(it) }
            ?: WorkerConfig.findForgeDir(java.nio.file.Paths.get("").toAbsolutePath())
        val result = SelfTest.run(forgeDir, GradleBuildRunner())
        println(result.report)
        exitProcess(if (result.ok) 0 else 1)
    }
    val config = try {
        WorkerConfig.fromEnv()
    } catch (e: IllegalArgumentException) {
        System.err.println("forge worker: ${e.message}")
        exitProcess(2)
    }
    log.info("starting: {}", config)

    val gateway = GatewayClient(config.gatewayUrl, config.workerToken)
    val runner = GradleBuildRunner()
    // A Meta client per job, on that job's key, closed when the job ends: the key lives no longer than its job.
    val worker = Worker(gateway, agent = { job, progress ->
        if (job.metaApiKey.isBlank()) {
            ForgeOutcome.Failed("No Meta key came with this request. Add your Meta key in Settings and ask again.")
        } else {
            MetaChatClient(
                baseUrl = config.metaBaseUrl,
                apiKey = job.metaApiKey,
                model = config.model,
                reasoningEffort = config.reasoningEffort,
            ).use { meta -> ForgeAgentLoop(meta, config.forgeDir, runner).run(job, progress) }
        }
    })

    try {
        runBlocking { worker.run(once = "--once" in args) }
    } finally {
        gateway.close()
    }
}
