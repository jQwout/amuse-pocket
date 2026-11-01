package dev.amuse.forge.worker

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path

/** A function the coding agent can call. [execute] never throws for bad arguments: it returns text the model reads. */
interface ForgeTool {
    val name: String
    val description: String

    /** JSON Schema of the arguments (`{type: object, properties, required}`). */
    val parameters: JsonObject

    fun execute(args: JsonObject): String

    fun schema() = ToolSchema(function = ToolSchema.Definition(name, description, parameters))
}

/** Where generated screens live, relative to the forge directory. */
const val SCREENS_ROOT = "screens/src/main/kotlin"
const val GENERATED_DIR = "$SCREENS_ROOT/dev/amuse/forge/screens/generated"

/**
 * Path rules for every file tool: the agent names files relative to the forge directory and can
 * neither leave it (`..`, absolute paths, drive letters, symlinks out) nor read anything but screen
 * sources and `AGENTS.md` - `keys/forge-private.key` sits right next to them and must never reach a model.
 */
class ForgeSandbox(forgeDir: Path) {
    val root: Path = forgeDir.toAbsolutePath().normalize()

    /** Returns the resolved path or throws [ToolError] with the reason the model should see. */
    fun resolveReadable(path: String): Path {
        val resolved = resolve(path)
        val rel = root.relativize(resolved).toString().replace('\\', '/')
        if (rel != "AGENTS.md" && !rel.startsWith("$SCREENS_ROOT/")) {
            throw ToolError("Path '$path' is outside the readable area. Only files under $SCREENS_ROOT/ can be read.")
        }
        return resolved
    }

    fun resolve(path: String): Path {
        val p = path.trim()
        if (p.isEmpty()) throw ToolError("Path is empty.")
        if (p.contains('\u0000')) throw ToolError("Path contains an invalid character.")
        val unified = p.replace('\\', '/')
        if (unified.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(unified) || unified.startsWith("~")) {
            throw ToolError("Absolute paths are not allowed: '$path'. Use a path relative to the forge directory.")
        }
        if (unified.split('/').any { it == ".." }) throw ToolError("Paths with '..' are not allowed: '$path'.")
        val resolved = root.resolve(unified).normalize()
        if (!resolved.startsWith(root)) throw ToolError("Path escapes the forge directory: '$path'.")
        // A symlink inside the tree must not lead out of it either.
        if (Files.exists(resolved) && !resolved.toRealPath().startsWith(root.toRealPath())) {
            throw ToolError("Path escapes the forge directory: '$path'.")
        }
        return resolved
    }
}

class ToolError(message: String) : Exception(message)

/**
 * What one job has done so far. The tools share it, and [ForgeAgentLoop] reads it to decide when to stop.
 *
 * The invariant that matters: [buildCurrent] is true only when `build_bundle` succeeded AFTER the last
 * `write_screen`. `finish(done)` is refused otherwise, so a bundle that does not match the source on
 * record can never be delivered.
 */
class ForgeSession {
    @Volatile var screenName: String? = null
    @Volatile var screenSource: String? = null
    @Volatile var buildCurrent: Boolean = false
    @Volatile var bundlePath: Path? = null
    @Volatile var lastBuildLog: String = ""
    /** Failed builds since the last `write_screen`; the loop gives up at [ForgeAgentLoop.MAX_BUILD_FAILURES]. */
    @Volatile var buildFailuresSinceWrite: Int = 0
    @Volatile var finish: FinishRequest? = null
}

enum class FinishStatus(val wire: String) {
    DONE("done"), NEEDS_INFO("needs_info"), FAILED("failed");

    companion object {
        fun fromWire(s: String?) = entries.firstOrNull { it.wire == s }
    }
}

data class FinishRequest(val status: FinishStatus, val entryPoint: String?, val question: String?, val notes: String?)

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private fun schemaOf(vararg props: Triple<String, String, String>, required: List<String> = emptyList(), enum: Map<String, List<String>> = emptyMap()) =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, type, description) in props) {
                putJsonObject(name) {
                    put("type", type)
                    put("description", description)
                    enum[name]?.let { values -> putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } } }
                }
            }
        }
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

class ListFilesTool(private val sandbox: ForgeSandbox) : ForgeTool {
    override val name = "list_files"
    override val description = "Lists the Kotlin files under $SCREENS_ROOT (paths relative to the forge directory)."
    override val parameters = schemaOf()

    override fun execute(args: JsonObject): String {
        val base = sandbox.root.resolve(SCREENS_ROOT)
        if (!Files.isDirectory(base)) return "(no files)"
        val files = Files.walk(base).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .map { sandbox.root.relativize(it).toString().replace('\\', '/') }
                .sorted().toList()
        }
        return files.joinToString("\n").ifEmpty { "(no files)" }
    }
}

class ReadFileTool(private val sandbox: ForgeSandbox) : ForgeTool {
    override val name = "read_file"
    override val description = "Reads a source file under $SCREENS_ROOT (or AGENTS.md). Path is relative to the forge directory, as list_files prints it."
    override val parameters = schemaOf(Triple("path", "string", "File path relative to the forge directory."), required = listOf("path"))

    override fun execute(args: JsonObject): String = try {
        val path = args.string("path") ?: throw ToolError("Missing 'path'.")
        val file = sandbox.resolveReadable(path)
        if (!Files.isRegularFile(file)) throw ToolError("No such file: '$path'.")
        val text = Files.readString(file)
        if (text.length > MAX_READ_CHARS) text.take(MAX_READ_CHARS) + "\n... (truncated at $MAX_READ_CHARS characters)" else text
    } catch (e: ToolError) {
        "ERROR: ${e.message}"
    } catch (e: java.io.IOException) {
        "ERROR: could not read the file: ${e.message}"
    }

    private companion object {
        const val MAX_READ_CHARS = 40_000
    }
}

class WriteScreenTool(
    private val sandbox: ForgeSandbox,
    private val session: ForgeSession,
) : ForgeTool {
    override val name = "write_screen"
    override val description = "Writes the screen to $GENERATED_DIR/<name>.kt, replacing any screen written earlier in this job. " +
        "name must look like WaterTrackerScreen; source is the complete Kotlin file. " +
        "It must declare @KetoyEntryPoint and `fun <name>()`. A package line is added for you."
    override val parameters = schemaOf(
        Triple("name", "string", "Screen function name: capital letter, letters and digits, ending in Screen."),
        Triple("source", "string", "The complete Kotlin source file."),
        required = listOf("name", "source"),
    )

    override fun execute(args: JsonObject): String = try {
        val name = args.string("name") ?: throw ToolError("Missing 'name'.")
        val source = args.string("source") ?: throw ToolError("Missing 'source'.")
        if (!NAME_RULE.matches(name)) throw ToolError("Invalid name '$name': it must match [A-Z][A-Za-z0-9]*Screen, for example WaterTrackerScreen.")
        if (name == "HelloForgeScreen") throw ToolError("HelloForgeScreen is the template and already exists; pick another name.")
        if (source.length > MAX_SOURCE_CHARS) throw ToolError("The source is longer than $MAX_SOURCE_CHARS characters; keep the screen smaller.")
        if (!source.contains("@KetoyEntryPoint")) throw ToolError("The source must contain @KetoyEntryPoint on the screen function.")
        if (!Regex("""fun\s+${Regex.escape(name)}\s*\(""").containsMatchIn(source)) {
            throw ToolError("The source must declare `fun $name(` - the function name has to equal the file's name argument.")
        }
        FORBIDDEN.firstOrNull { it.first.containsMatchIn(source) }?.let { throw ToolError("Not allowed in a Ketoy screen: ${it.second}.") }
        if (Regex("""@Composable""").findAll(source).count() > 1) {
            throw ToolError("Not allowed in a Ketoy screen: more than one @Composable function - inline the helper into the entry function (helper composables crash the interpreter at runtime).")
        }

        val normalized = withPackage(source)
        val dir = sandbox.resolve(GENERATED_DIR)
        Files.createDirectories(dir)
        // One job = one screen besides the template: whatever an earlier attempt left would be bundled too.
        Files.newDirectoryStream(dir, "*.kt").use { stream -> stream.forEach { Files.deleteIfExists(it) } }
        val target = sandbox.resolve("$GENERATED_DIR/$name.kt")
        Files.writeString(target, normalized)

        session.screenName = name
        session.screenSource = normalized
        session.buildCurrent = false
        session.buildFailuresSinceWrite = 0
        "OK: wrote ${sandbox.root.relativize(target).toString().replace('\\', '/')} (${normalized.length} characters). Now call build_bundle."
    } catch (e: ToolError) {
        "ERROR: ${e.message}"
    } catch (e: java.io.IOException) {
        "ERROR: could not write the file: ${e.message}"
    }

    private fun withPackage(source: String): String {
        val withoutPackage = source.lines().filterNot { it.trimStart().startsWith("package ") }.joinToString("\n").trimStart()
        return "package $GENERATED_PACKAGE\n\n$withoutPackage" + if (source.endsWith("\n")) "" else "\n"
    }

    companion object {
        const val GENERATED_PACKAGE = "dev.amuse.forge.screens.generated"
        const val MAX_SOURCE_CHARS = 60_000
        val NAME_RULE = Regex("[A-Z][A-Za-z0-9]*Screen")

        /** Cheap pre-checks for what the Ketoy compiler rejects anyway; saves a two-minute build round trip. */
        private val FORBIDDEN = listOf(
            Regex("""^\s*import\s+android\.""", RegexOption.MULTILINE) to "android.* imports (Android APIs are unavailable in the sandbox)",
            Regex("""\bGlobalScope\b""") to "GlobalScope",
            // One function per screen: helper composables with parameters crash the interpreter at runtime.
            Regex("""\bclickable\b""") to "Modifier.clickable - use a Button",
            Regex("""\(\s*\)\s*->\s*Unit|:\s*\([^)]*\)\s*->""") to "lambda parameters - keep every tap handler inline in the entry function",
            // Ranges compile but the KBC interpreter has no Int.rangeTo: the screen crashes the app at runtime.
            Regex("""\d\s*\.\.\s*[\d(a-zA-Z]|\)\s*\.\.|\buntil\b|\bdownTo\b|\bstep\b|\.indices\b|\brangeTo\b""") to
                "ranges (.., until, downTo, step, .indices) - they compile but crash the app at runtime; loop with repeat(n) { i -> } or forEachIndexed",
            Regex("""\brunBlocking\b""") to "runBlocking",
            Regex("""::class\.java|import\s+kotlin\.reflect|import\s+java\.lang\.reflect""") to "reflection",
            Regex("""^\s*import\s+java\.(io|nio|net)\.""", RegexOption.MULTILINE) to "java.io / java.nio / java.net (no file or network access)",
            Regex("""^\s*import\s+kotlin\.io\.""", RegexOption.MULTILINE) to "kotlin.io (no file access)",
        )
    }
}

class BuildBundleTool(
    private val sandbox: ForgeSandbox,
    private val session: ForgeSession,
    private val runner: BuildRunner,
) : ForgeTool {
    override val name = "build_bundle"
    override val description = "Compiles the screens module with the Ketoy compiler into a signed bundle. " +
        "Takes a minute or two. Returns {\"ok\": true|false, \"log\": \"...last lines of the build output...\"}."
    override val parameters = schemaOf()

    override fun execute(args: JsonObject): String {
        if (session.screenName == null) {
            return buildResult(false, "No screen has been written yet. Call write_screen first.")
        }
        val result = try {
            runner.run(sandbox.root)
        } catch (e: Exception) {
            BuildResult(false, "The build could not be started: ${e.message}", null)
        }
        session.lastBuildLog = result.log
        if (result.ok && result.bundle != null) {
            session.buildCurrent = true
            session.bundlePath = result.bundle
            session.buildFailuresSinceWrite = 0
        } else {
            session.buildCurrent = false
            session.buildFailuresSinceWrite += 1
        }
        return buildResult(result.ok && result.bundle != null, result.log)
    }

    private fun buildResult(ok: Boolean, log: String): String = buildJsonObject {
        put("ok", ok)
        put("log", log)
    }.toString()
}

class FinishTool(private val session: ForgeSession) : ForgeTool {
    override val name = "finish"
    override val description = "Ends the job. status=done only after build_bundle succeeded on the current source (the call is rejected otherwise). " +
        "status=needs_info when the request cannot be built without an answer from the person: give question. status=failed when it cannot be done: give notes."
    override val parameters = schemaOf(
        Triple("status", "string", "done, needs_info or failed."),
        Triple("entryPoint", "string", "For done: the screen function name. Defaults to the screen last written."),
        Triple("question", "string", "For needs_info: one short question for the person, in their language."),
        Triple("notes", "string", "Short plain-language summary, shown to the person."),
        required = listOf("status"),
        enum = mapOf("status" to FinishStatus.entries.map { it.wire }),
    )

    override fun execute(args: JsonObject): String {
        val status = FinishStatus.fromWire(args.string("status"))
            ?: return "ERROR: status must be one of done, needs_info, failed."
        val entryPoint = args.string("entryPoint")?.takeIf { it.isNotBlank() }
        val question = args.string("question")?.takeIf { it.isNotBlank() }
        val notes = args.string("notes")?.takeIf { it.isNotBlank() }
        when (status) {
            FinishStatus.DONE -> {
                val written = session.screenName
                    ?: return "ERROR: finish(done) rejected: no screen has been written. Call write_screen, then build_bundle."
                if (!session.buildCurrent) {
                    return "ERROR: finish(done) rejected: build_bundle has not succeeded since the last write_screen. " +
                        "Call build_bundle and fix any errors first."
                }
                if (entryPoint != null && entryPoint != written) {
                    return "ERROR: finish(done) rejected: entryPoint '$entryPoint' is not the screen that was written ('$written')."
                }
                session.finish = FinishRequest(status, written, null, notes)
            }
            FinishStatus.NEEDS_INFO -> {
                if (question == null) return "ERROR: finish(needs_info) needs a non-empty 'question'."
                session.finish = FinishRequest(status, null, question, notes)
            }
            FinishStatus.FAILED -> session.finish = FinishRequest(status, null, null, notes)
        }
        return "OK: job finished with status ${status.wire}."
    }
}

fun forgeTools(sandbox: ForgeSandbox, session: ForgeSession, runner: BuildRunner): List<ForgeTool> = listOf(
    ListFilesTool(sandbox),
    ReadFileTool(sandbox),
    WriteScreenTool(sandbox, session),
    BuildBundleTool(sandbox, session, runner),
    FinishTool(session),
)
