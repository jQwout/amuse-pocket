package dev.amuse.forge.worker

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Everything the worker reads from the environment, in one place so [main] stays a thin shell and
 * tests can build a config without touching the process environment.
 *
 * The keys are never logged: [toString] is overridden to say so explicitly.
 */
data class WorkerConfig(
    val gatewayUrl: String,
    val workerToken: String,
    val metaApiKey: String,
    val model: String = DEFAULT_MODEL,
    val metaBaseUrl: String = DEFAULT_META_BASE_URL,
    val forgeDir: Path,
    /** Top-level `reasoning_effort` for Meta's reasoning models; null omits the field. */
    val reasoningEffort: String? = DEFAULT_REASONING_EFFORT,
) {
    override fun toString(): String =
        "WorkerConfig(gatewayUrl=$gatewayUrl, model=$model, metaBaseUrl=$metaBaseUrl, forgeDir=$forgeDir, keys=<hidden>)"

    companion object {
        const val DEFAULT_MODEL = "muse-spark-1.3"
        const val DEFAULT_META_BASE_URL = "https://api.meta.ai/v1"
        const val DEFAULT_REASONING_EFFORT = "medium"

        /** Returns the config or throws [IllegalArgumentException] naming every missing variable. */
        fun fromEnv(
            env: Map<String, String> = System.getenv(),
            workingDir: Path = Paths.get("").toAbsolutePath(),
        ): WorkerConfig {
            val missing = listOf("FORGE_GATEWAY_URL", "FORGE_WORKER_TOKEN", "META_API_KEY")
                .filter { env[it].isNullOrBlank() }
            require(missing.isEmpty()) { "Missing environment variables: ${missing.joinToString()}" }
            // FORGE_REASONING_EFFORT unset -> default; set to blank or "off" -> the field is omitted.
            val effort = if (env.containsKey("FORGE_REASONING_EFFORT")) {
                env.getValue("FORGE_REASONING_EFFORT").trim().takeIf { it.isNotEmpty() && !it.equals("off", ignoreCase = true) }
            } else {
                DEFAULT_REASONING_EFFORT
            }
            return WorkerConfig(
                gatewayUrl = env.getValue("FORGE_GATEWAY_URL").trim().trimEnd('/'),
                workerToken = env.getValue("FORGE_WORKER_TOKEN").trim(),
                metaApiKey = env.getValue("META_API_KEY").trim(),
                model = env["FORGE_MODEL"]?.takeIf { it.isNotBlank() }?.trim() ?: DEFAULT_MODEL,
                metaBaseUrl = (env["META_BASE_URL"]?.takeIf { it.isNotBlank() }?.trim() ?: DEFAULT_META_BASE_URL).trimEnd('/'),
                forgeDir = env["FORGE_DIR"]?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath().normalize() }
                    ?: findForgeDir(workingDir),
                reasoningEffort = effort,
            )
        }

        /**
         * The forge directory is the one holding `settings.gradle.kts` and a `screens` module. Started from
         * the repo root, `forge/worker` or `forge` itself, the right one is found by walking up, checking
         * a `forge` child at every level.
         */
        fun findForgeDir(start: Path): Path {
            fun isForge(p: Path) =
                Files.isRegularFile(p.resolve("settings.gradle.kts")) && Files.isDirectory(p.resolve("screens"))
            var dir: Path? = start.toAbsolutePath().normalize()
            while (dir != null) {
                if (isForge(dir)) return dir
                if (isForge(dir.resolve("forge"))) return dir.resolve("forge")
                dir = dir.parent
            }
            return start.toAbsolutePath().normalize()
        }
    }
}
