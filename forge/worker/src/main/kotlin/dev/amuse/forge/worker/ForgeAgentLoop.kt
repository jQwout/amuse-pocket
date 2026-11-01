package dev.amuse.forge.worker

import dev.amuse.forge.contract.WorkerJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** How a job ended, as the worker reports it to the gateway. */
sealed interface ForgeOutcome {
    class Done(val entryPoint: String, val bundleBytes: ByteArray, val source: String) : ForgeOutcome
    data class NeedsInfo(val question: String) : ForgeOutcome
    data class Failed(val message: String) : ForgeOutcome
}

/**
 * The coding agent: Muse writes a screen through [ForgeTool]s until it calls `finish`.
 *
 * Every limit lives here, in code, not in the prompt: [maxModelCalls] model calls, [timeoutMs] in
 * total, [MAX_BUILD_FAILURES] failed builds in a row without a new `write_screen`, and three replies in
 * a row that call no tool at all. Whichever trips first turns into [ForgeOutcome.Failed] with the tail of
 * the last build log, so the person (and the owner reading the gateway) sees why.
 */
class ForgeAgentLoop(
    private val chat: MetaChatClient,
    private val forgeDir: Path,
    private val runner: BuildRunner,
    private val maxModelCalls: Int = MAX_MODEL_CALLS,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(ForgeAgentLoop::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun run(job: WorkerJob, onProgress: suspend (String) -> Unit = {}): ForgeOutcome {
        val sandbox = ForgeSandbox(forgeDir)
        val session = ForgeSession()
        val tools = forgeTools(sandbox, session, runner).associateBy { it.name }
        val schemas = tools.values.map { it.schema() }

        val system = try {
            PromptBuilder.system(sandbox.root)
        } catch (e: java.io.IOException) {
            return ForgeOutcome.Failed("The worker is misconfigured: ${e.message}")
        }
        clearGenerated(sandbox)

        val messages = mutableListOf(ChatMessage.system(system), ChatMessage.user(PromptBuilder.user(job)))
        val deadline = clock() + timeoutMs
        var calls = 0
        var silentReplies = 0

        while (true) {
            if (clock() >= deadline) return failed(session, "The agent ran out of time (${timeoutMs / 60_000} minutes).")
            if (calls >= maxModelCalls) return failed(session, "The agent used all $maxModelCalls steps without finishing.")
            calls++

            val reply = try {
                chat.chat(messages, schemas)
            } catch (e: CancellationException) {
                throw e
            } catch (e: MetaApiException) {
                return ForgeOutcome.Failed("The model service failed: ${e.message}")
            }
            log.info("model call {}/{}: {} tool call(s), finish_reason={}", calls, maxModelCalls, reply.toolCalls.size, reply.finishReason)
            messages += ChatMessage.assistant(reply)

            if (reply.toolCalls.isEmpty()) {
                // A chatty reply with no tool call would end the loop silently; push the model back on the rails.
                if (++silentReplies >= MAX_SILENT_REPLIES) {
                    return failed(session, "The agent stopped calling tools without finishing.")
                }
                messages += ChatMessage.user("Continue by calling a tool. When the screen is built, or you cannot proceed, call finish.")
                continue
            }
            silentReplies = 0

            for (call in reply.toolCalls) {
                val result = executeCall(tools, call, onProgress)
                messages += ChatMessage.tool(call.id, result.take(MAX_TOOL_RESULT_CHARS))
                if (session.finish != null) break
            }

            session.finish?.let { return outcomeOf(it, session) }
            if (session.buildFailuresSinceWrite >= MAX_BUILD_FAILURES) {
                return failed(session, "The screen did not compile after $MAX_BUILD_FAILURES attempts in a row.")
            }
        }
    }

    private suspend fun executeCall(tools: Map<String, ForgeTool>, call: ToolCall, onProgress: suspend (String) -> Unit): String {
        val tool = tools[call.function.name] ?: return "ERROR: unknown tool '${call.function.name}'. Available: ${tools.keys.joinToString()}."
        val args = try {
            json.parseToJsonElement(call.function.arguments.ifBlank { "{}" }) as? JsonObject
                ?: return "ERROR: the arguments of ${tool.name} must be a JSON object."
        } catch (e: Exception) {
            return "ERROR: the arguments of ${tool.name} are not valid JSON: ${e.message?.take(120)}"
        }
        when (tool.name) {
            "write_screen" -> progress(onProgress, "Writing the screen")
            "build_bundle" -> progress(onProgress, "Compiling the screen")
        }
        log.info("tool {}", tool.name)
        return try {
            withContext(Dispatchers.IO) { tool.execute(args) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            "ERROR: ${tool.name} failed: ${e::class.simpleName}: ${e.message}"
        }.also { log.info("tool {} -> {}", tool.name, it.lineSequence().firstOrNull()?.take(160)) }
    }

    private suspend fun progress(onProgress: suspend (String) -> Unit, text: String) {
        try {
            onProgress(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("progress report failed: {}", e.message)
        }
    }

    private fun outcomeOf(finish: FinishRequest, session: ForgeSession): ForgeOutcome = when (finish.status) {
        FinishStatus.NEEDS_INFO -> ForgeOutcome.NeedsInfo(finish.question.orEmpty())
        FinishStatus.FAILED -> failed(session, finish.notes ?: "The agent could not build this screen.")
        FinishStatus.DONE -> {
            val bundle = session.bundlePath
            val source = session.screenSource
            val entry = finish.entryPoint
            if (bundle == null || source == null || entry == null) {
                ForgeOutcome.Failed("Internal error: finish(done) was accepted without a bundle.")
            } else {
                try {
                    ForgeOutcome.Done(entry, Files.readAllBytes(bundle), source)
                } catch (e: java.io.IOException) {
                    ForgeOutcome.Failed("The bundle could not be read back: ${e.message}")
                }
            }
        }
    }

    private fun failed(session: ForgeSession, reason: String): ForgeOutcome.Failed {
        val tail = session.lastBuildLog.lines().takeLast(LOG_TAIL_LINES).joinToString("\n").trim()
        return ForgeOutcome.Failed(if (tail.isEmpty()) reason else "$reason\nLast build output:\n$tail")
    }

    private fun clearGenerated(sandbox: ForgeSandbox) {
        val dir = sandbox.resolve(GENERATED_DIR)
        if (Files.isDirectory(dir)) Files.newDirectoryStream(dir, "*.kt").use { s -> s.forEach { Files.deleteIfExists(it) } }
    }

    companion object {
        const val MAX_MODEL_CALLS = 25
        const val TIMEOUT_MS = 15L * 60 * 1000
        const val MAX_BUILD_FAILURES = 3
        private const val MAX_SILENT_REPLIES = 3
        private const val MAX_TOOL_RESULT_CHARS = 12_000
        private const val LOG_TAIL_LINES = 20
    }
}
