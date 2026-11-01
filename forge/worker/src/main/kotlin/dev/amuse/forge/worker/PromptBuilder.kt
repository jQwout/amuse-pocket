package dev.amuse.forge.worker

import dev.amuse.forge.contract.WorkerJob
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Assembles what the model reads. The rules live in `forge/AGENTS.md` so the owner can tune them
 * without a rebuild; the component catalog ships inside the jar because it must match the Ketoy
 * version the worker was built against.
 */
object PromptBuilder {
    private const val TEMPLATE_PATH = "screens/src/main/kotlin/dev/amuse/forge/screens/HelloForgeScreen.kt"

    /** @throws IOException when AGENTS.md or the template is missing - the worker cannot work without them. */
    fun system(forgeDir: Path): String {
        val agents = read(forgeDir.resolve("AGENTS.md"), "AGENTS.md")
        val template = read(forgeDir.resolve(TEMPLATE_PATH), TEMPLATE_PATH)
        val catalog = PromptBuilder::class.java.getResourceAsStream("/ketoy-catalog.md")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("ketoy-catalog.md is missing from the worker jar")
        return buildString {
            append(agents.trim()).append("\n\n")
            append("# Template screen (HelloForgeScreen.kt)\n\n```kotlin\n").append(template.trim()).append("\n```\n\n")
            append(catalog.trim()).append('\n')
        }
    }

    fun user(job: WorkerJob): String = buildString {
        append("Agent name: ").append(job.agentName).append('\n')
        if (job.agentDescription.isNotBlank()) append("Agent description: ").append(job.agentDescription).append('\n')
        append("\nThe person asks for this screen:\n").append(job.request.trim()).append('\n')
        val previous = job.previousSource
        if (previous != null) {
            val entry = job.previousEntryPoint
            append("\nThis is an UPDATE of an existing screen")
            if (entry != null) append(" (function ").append(entry).append("; keep this name)")
            append(". Change it as requested and keep everything else. Its current source:\n```kotlin\n")
            append(previous.trim()).append("\n```\n")
        }
        if (job.exchanges.isNotEmpty()) {
            append("\nYou asked questions earlier; the person answered. Do not ask these again:\n")
            job.exchanges.forEachIndexed { i, e ->
                append(i + 1).append(". Q: ").append(e.question).append("\n   A: ").append(e.answer).append('\n')
            }
        }
        append("\nStart now: write_screen, then build_bundle, then finish.")
    }

    private fun read(path: Path, label: String): String =
        try {
            Files.readString(path)
        } catch (e: IOException) {
            throw IOException("$label not found in the forge directory (${e.message})", e)
        }
}
