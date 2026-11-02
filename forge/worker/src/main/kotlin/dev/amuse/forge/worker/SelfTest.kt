package dev.amuse.forge.worker

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/**
 * `--self-test`: runs the tool path a job would run - `write_screen`, `build_bundle`, `finish` - with a
 * fixed sample screen and no model, no gateway and no keys. It answers "is this machine able to
 * build a bundle?" before a demo, in about a minute. The generated screen is removed afterwards.
 */
object SelfTest {
    const val SAMPLE_NAME = "SelfTestScreen"

    private val SAMPLE_SOURCE = """
        import androidx.compose.foundation.layout.Arrangement
        import androidx.compose.foundation.layout.Column
        import androidx.compose.foundation.layout.fillMaxWidth
        import androidx.compose.foundation.layout.padding
        import androidx.compose.material3.Button
        import androidx.compose.material3.Text
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.runtime.mutableIntStateOf
        import androidx.compose.runtime.remember
        import androidx.compose.runtime.setValue
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.unit.dp
        import dev.ketoy.annotations.KetoyComposable
        import dev.ketoy.annotations.KetoyEntryPoint

        @KetoyEntryPoint
        @KetoyComposable
        @Composable
        fun $SAMPLE_NAME() {
            var glasses by remember { mutableIntStateOf(0) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "Glasses: ${'$'}glasses of 8")
                Button(onClick = { if (glasses < 8) glasses += 1 }) { Text(text = "+1 glass") }
                Button(onClick = { glasses = 0 }) { Text(text = "Reset") }
            }
        }
    """.trimIndent() + "\n"

    /** Returns a human-readable report; [Result.ok] is false when any step failed. */
    data class Result(val ok: Boolean, val report: String, val bundleBytes: Int = 0)

    fun run(forgeDir: Path, runner: BuildRunner): Result {
        val sandbox = ForgeSandbox(forgeDir)
        val session = ForgeSession()
        val tools = forgeTools(sandbox, session, runner).associateBy { it.name }
        try {
            val write = tools.getValue("write_screen").execute(
                buildJsonObject { put("name", SAMPLE_NAME); put("source", SAMPLE_SOURCE) },
            )
            if (!write.startsWith("OK")) return Result(false, "write_screen: $write")
            val build = tools.getValue("build_bundle").execute(buildJsonObject {})
            if (!session.buildCurrent) return Result(false, "build_bundle failed:\n$build")
            val finish = tools.getValue("finish").execute(buildJsonObject { put("status", "done") })
            if (session.finish?.status != FinishStatus.DONE) return Result(false, "finish: $finish")
            val size = Files.size(session.bundlePath!!).toInt()
            return Result(true, "OK: built ${session.finish?.entryPoint}, bundle $size bytes at ${session.bundlePath}", size)
        } finally {
            val dir = sandbox.resolve(GENERATED_DIR)
            if (Files.isDirectory(dir)) {
                Files.newDirectoryStream(dir, "*.kt").use { s -> s.forEach { Files.deleteIfExists(it) } }
            }
        }
    }
}
