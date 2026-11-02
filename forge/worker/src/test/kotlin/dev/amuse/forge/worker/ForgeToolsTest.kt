package dev.amuse.forge.worker

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ForgeToolsTest {
    private val dir: Path = makeForgeDir()
    private val sandbox = ForgeSandbox(dir)
    private val session = ForgeSession()
    private val fake = FakeBuild(mutableListOf(), dir)
    private val tools = forgeTools(sandbox, session, fake).associateBy { it.name }

    private fun call(tool: String, vararg args: Pair<String, String>): String =
        tools.getValue(tool).execute(Json.parseToJsonElement(jsonArgs(*args)) as JsonObject)

    private fun write(name: String = "WaterTrackerScreen", source: String = SCREEN_SOURCE) =
        call("write_screen", "name" to name, "source" to source)

    @Test
    fun `the sandbox itself rejects traversal and absolute paths for any tool`() {
        for (bad in listOf("../no-such-file-4242", "a/../../no-such-file-4242", "a\\..\\..\\x", "/abs", "\\abs", "C:\\x", "d:/x")) {
            assertFailsWith<ToolError>(bad) { sandbox.resolve(bad) }
        }
        assertEquals(dir.toAbsolutePath().normalize().resolve("a/b.kt"), sandbox.resolve("a/./b.kt"))
    }

    @Test
    fun `read_file refuses parent traversal and absolute paths`() {
        for (bad in listOf(
            "../outside.txt", "screens/../../outside.txt", "..\\outside.txt",
            "/etc/passwd", "C:\\Windows\\win.ini", "c:/Windows/win.ini", "~/x", "",
        )) {
            val result = call("read_file", "path" to bad)
            assertTrue(result.startsWith("ERROR"), "'$bad' must be rejected, got: $result")
        }
    }

    @Test
    fun `read_file reads screens and AGENTS but not keys or other forge files`() {
        assertContains(call("read_file", "path" to "$SCREENS_ROOT/dev/amuse/forge/screens/HelloForgeScreen.kt"), "HelloForgeScreen")
        assertContains(call("read_file", "path" to "AGENTS.md"), "Test agent rules")
        val secret = call("read_file", "path" to "secret.key")
        assertTrue(secret.startsWith("ERROR"))
        assertFalse(secret.contains("private"))
    }

    @Test
    fun `read_file refuses a symlink that points out of the forge directory`() {
        val outside = Files.createTempFile("outside", ".txt").also { Files.writeString(it, "leak") }
        val link = dir.resolve("$SCREENS_ROOT/link.kt")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (e: Exception) {
            return // symlinks need a privilege on Windows; nothing to test there
        }
        val result = call("read_file", "path" to "$SCREENS_ROOT/link.kt")
        assertTrue(result.startsWith("ERROR"), result)
        assertFalse(result.contains("leak"))
    }

    @Test
    fun `list_files lists screen sources relative to the forge directory`() {
        assertEquals("$SCREENS_ROOT/dev/amuse/forge/screens/HelloForgeScreen.kt", call("list_files"))
    }

    @Test
    fun `write_screen writes exactly the generated file and records the source`() {
        val result = write()

        assertTrue(result.startsWith("OK"), result)
        val file = dir.resolve("$GENERATED_DIR/WaterTrackerScreen.kt")
        assertTrue(Files.exists(file))
        assertContains(Files.readString(file), "package dev.amuse.forge.screens.generated")
        assertEquals("WaterTrackerScreen", session.screenName)
        assertEquals(Files.readString(file), session.screenSource)
    }

    @Test
    fun `write_screen rejects bad names without touching the disk`() {
        for (bad in listOf("waterScreen", "Water", "../EvilScreen", "Evil Screen", "HelloForgeScreen")) {
            val result = write(name = bad, source = SCREEN_SOURCE.replace("WaterTrackerScreen", bad))
            assertTrue(result.startsWith("ERROR"), "'$bad' -> $result")
        }
        assertFalse(Files.exists(dir.resolve(GENERATED_DIR)))
        assertNull(session.screenName)
    }

    @Test
    fun `write_screen requires the entry point annotation and a matching function`() {
        assertContains(write(source = SCREEN_SOURCE.replace("@KetoyEntryPoint", "")), "@KetoyEntryPoint")
        assertContains(write(source = SCREEN_SOURCE.replace("fun WaterTrackerScreen(", "fun OtherScreen(")), "fun WaterTrackerScreen(")
        assertNull(session.screenName)
    }

    @Test
    fun `write_screen rejects what the Ketoy sandbox forbids`() {
        for (forbidden in listOf("import android.util.Log", "import java.io.File", "val s = GlobalScope", "val c = String::class.java")) {
            val result = write(source = "$forbidden\n$SCREEN_SOURCE")
            assertContains(result, "Not allowed", message = forbidden)
        }
    }

    @Test
    fun `write_screen replaces the previous generated screen`() {
        write(name = "FirstScreen", source = SCREEN_SOURCE.replace("WaterTrackerScreen", "FirstScreen"))
        write(name = "SecondScreen", source = SCREEN_SOURCE.replace("WaterTrackerScreen", "SecondScreen"))

        val names = Files.list(dir.resolve(GENERATED_DIR)).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("SecondScreen.kt"), names)
    }

    @Test
    fun `write_screen invalidates an earlier successful build`() {
        write()
        call("build_bundle")
        assertTrue(session.buildCurrent)

        write()

        assertFalse(session.buildCurrent)
    }

    @Test
    fun `build_bundle before any screen is an error and does not run Gradle`() {
        val result = call("build_bundle")

        assertContains(result, "\"ok\":false")
        assertEquals(0, fake.calls)
    }

    @Test
    fun `build_bundle reports ok and records the bundle`() {
        write()

        val result = call("build_bundle")

        assertContains(result, "\"ok\":true")
        assertTrue(session.buildCurrent)
        assertEquals(dir.resolve("agent.ktx"), session.bundlePath)
    }

    @Test
    fun `finish done is rejected until a build succeeded on the current source`() {
        assertContains(call("finish", "status" to "done"), "rejected")
        write()
        assertContains(call("finish", "status" to "done"), "rejected")
        assertNull(session.finish)

        call("build_bundle")
        assertTrue(call("finish", "status" to "done").startsWith("OK"))
        assertEquals(FinishStatus.DONE, session.finish?.status)
        assertEquals("WaterTrackerScreen", session.finish?.entryPoint)
    }

    @Test
    fun `finish done refuses an entry point that was not written`() {
        write()
        call("build_bundle")

        assertContains(call("finish", "status" to "done", "entryPoint" to "OtherScreen"), "rejected")
        assertNull(session.finish)
    }

    @Test
    fun `finish needs_info requires a question`() {
        assertContains(call("finish", "status" to "needs_info"), "question")
        assertNull(session.finish)
        assertTrue(call("finish", "status" to "needs_info", "question" to "Which units?").startsWith("OK"))
        assertEquals("Which units?", session.finish?.question)
    }

    @Test
    fun `every tool schema is a function with an object parameter block`() {
        for (tool in tools.values) {
            val schema = tool.schema()
            assertEquals("function", schema.type)
            assertEquals("object", (schema.function.parameters["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        }
        assertEquals(setOf("list_files", "read_file", "write_screen", "build_bundle", "finish"), tools.keys)
    }

    @Test
    fun `the build summary keeps compiler errors that scrolled out of the tail`() {
        val output = listOf("e: file:///x/Foo.kt:3:4 Unresolved reference") +
            List(100) { "> Task :screens:something$it" } + List(80) { "noise line $it" }

        val summary = GradleBuildRunner.summarize(output, dir, null)

        assertContains(summary, "Unresolved reference")
        assertContains(summary, "noise line 79")
        assertFalse(summary.contains("> Task"))
    }

    @Test
    fun `write_screen rejects ranges because the interpreter cannot run them`() {
        for (range in listOf("for (i in 0..7) {}", "for (i in 0 until 8) {}", "for (i in 7 downTo 0) {}", "val r = (0..7).step(2)", "list.indices.forEach {}", "(0 until n).map { it }")) {
            val result = write(source = SCREEN_SOURCE.replace("fun WaterTrackerScreen() {", "fun WaterTrackerScreen() {\n    $range"))
            assertContains(result, "ranges", message = range)
        }
        // A plain decimal, a string with dots and a safe-call chain are not ranges.
        val ok = write(source = SCREEN_SOURCE.replace("fun WaterTrackerScreen() {", "fun WaterTrackerScreen() {\n    val v = 1.5\n    val s = \"wait...\"\n    repeat(8) { i -> val n = i + 1 }"))
        assertContains(ok, "OK")
    }


    @Test
    fun `write_screen rejects helper composables, lambda parameters and clickable`() {
        val helper = "@KetoyComposable\n@Composable\nfun Cell(n: Int) { }\n"
        assertContains(write(source = helper + SCREEN_SOURCE), "more than one @Composable")
        val lambdaParam = SCREEN_SOURCE.replace("fun WaterTrackerScreen() {", "fun WaterTrackerScreen() {\n    val tap: () -> Unit = {}")
        assertContains(write(source = lambdaParam), "lambda parameters")
        val clickable = SCREEN_SOURCE.replace("fun WaterTrackerScreen() {", "fun WaterTrackerScreen() {\n    val m = Modifier.clickable { }")
        assertContains(write(source = clickable), "clickable")
    }


    @Test
    fun `write_screen ignores strings and comments when it looks for forbidden code`() {
        val harmless = SCREEN_SOURCE.replace(
            "fun WaterTrackerScreen() {",
            "// a @Composable helper would crash: 0..7, until, clickable\n/* step */\nfun WaterTrackerScreen() {\n    val label = \"next step: 1..8 until done\"\n    val raw = \"\"\"downTo .indices @Composable\"\"\"",
        )
        assertContains(write(source = harmless), "OK")
        assertContains(write(source = SCREEN_SOURCE.replace("fun WaterTrackerScreen() {", "fun WaterTrackerScreen() {\n    val s = \"x\"\n    repeat(2) { i -> val r = i until 4 }")), "ranges")
    }


    @Test
    fun `write_screen rejects numeric conversions, derivedStateOf and nullables`() {
        for (bad in listOf("val f = count.toFloat()", "val d = derivedStateOf { 1 }", "var name: String? = null", "val x = maybe ?: 0")) {
            assertContains(write(source = SCREEN_SOURCE.replace("fun WaterTrackerScreen() {", "fun WaterTrackerScreen() {\n    $bad")), "Not allowed", message = bad)
        }
    }

}
