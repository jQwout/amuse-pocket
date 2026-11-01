package dev.amuse.forge.worker

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** [bundle] is set only when the build succeeded and the `.ktx` is on disk. [log] is what the model reads. */
data class BuildResult(val ok: Boolean, val log: String, val bundle: Path?)

/** Seam for tests: the loop never needs a real Gradle. */
fun interface BuildRunner {
    fun run(forgeDir: Path): BuildResult
}

/**
 * Runs `gradlew :screens:ketoyBundle` in the forge directory.
 *
 * The bundle is accepted only if its file is newer than the start of this build: a failed Ketoy step
 * must never let a leftover `agent.ktx` from an earlier job slip through as "success".
 */
class GradleBuildRunner(
    private val timeoutMinutes: Long = 10,
    private val windows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
) : BuildRunner {
    private val log = LoggerFactory.getLogger(GradleBuildRunner::class.java)

    override fun run(forgeDir: Path): BuildResult {
        val startedAt = System.currentTimeMillis()
        val command = if (windows) {
            listOf(forgeDir.resolve("gradlew.bat").toString())
        } else {
            // `sh gradlew` works even where the checkout lost the executable bit.
            listOf("sh", forgeDir.resolve("gradlew").toString())
        } + listOf(":screens:ketoyBundle", "--console=plain")

        log.info("build: {}", command.joinToString(" "))
        val process = try {
            ProcessBuilder(command).directory(forgeDir.toFile()).redirectErrorStream(true).start()
        } catch (e: java.io.IOException) {
            return BuildResult(false, "Could not start Gradle: ${e.message}", null)
        }
        process.outputStream.close()

        // Read on a separate thread: a full pipe would block Gradle, and waitFor must be able to time out.
        val lines = ArrayDeque<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { seq ->
                seq.forEach { line ->
                    synchronized(lines) {
                        lines.addLast(line)
                        if (lines.size > KEEP_LINES) lines.removeFirst()
                    }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
        reader.join(5_000)
        val output = synchronized(lines) { lines.toList() }

        if (!finished) {
            return BuildResult(false, summarize(output, forgeDir, "The build timed out after $timeoutMinutes minutes and was stopped."), null)
        }
        val exit = process.exitValue()
        val bundle = forgeDir.resolve(BUNDLE_PATH)
        val fresh = Files.isRegularFile(bundle) && Files.getLastModifiedTime(bundle).toMillis() >= startedAt - 2_000
        return when {
            exit == 0 && fresh -> BuildResult(true, summarize(output, forgeDir, null), bundle)
            exit == 0 -> BuildResult(false, summarize(output, forgeDir, "Gradle succeeded but produced no new bundle at $BUNDLE_PATH."), null)
            else -> BuildResult(false, summarize(output, forgeDir, null), null)
        }
    }

    companion object {
        const val BUNDLE_PATH = "screens/build/ketoy-bundles/agent.ktx"
        private const val KEEP_LINES = 600
        private const val TAIL_LINES = 60
        private const val MAX_ERROR_LINES = 30

        /**
         * The last [TAIL_LINES] lines, as the spec asks, preceded by the compiler's `e:` lines when they
         * would otherwise scroll out of the tail - Gradle ends a failure with a long "What went wrong" block
         * and the actual error is above it. Absolute paths are shortened to be relative to the forge directory.
         */
        fun summarize(output: List<String>, forgeDir: Path, headline: String?): String {
            val root = forgeDir.toAbsolutePath().normalize().toString().replace('\\', '/')
            fun clean(line: String) = line.replace('\\', '/').replace("file:///", "file://").replace("$root/", "")
            // Gradle's task list, blank lines and deprecation boilerplate carry nothing the model can act on.
            val relevant = output.filterNot { line -> line.isBlank() || NOISE.any { line.startsWith(it) } }
            val tail = relevant.takeLast(TAIL_LINES)
            val errors = relevant.dropLast(TAIL_LINES).filter { it.startsWith("e: ") || it.contains("KetoyBC") }.take(MAX_ERROR_LINES)
            return buildList {
                headline?.let { add(it) }
                if (errors.isNotEmpty()) {
                    add("--- compiler errors (earlier in the output) ---")
                    addAll(errors.map(::clean))
                    add("--- last $TAIL_LINES lines ---")
                }
                addAll(tail.map(::clean))
            }.joinToString("\n").take(MAX_LOG_CHARS)
        }

        private const val MAX_LOG_CHARS = 9_000
        private val NOISE = listOf(
            "> Task ", "[Incubating]", "Deprecated Gradle", "You can use '--warning-mode", "For more on this",
            "* Try:", "> Run with", "> Get more help", "Starting a Gradle Daemon",
        )
    }
}
