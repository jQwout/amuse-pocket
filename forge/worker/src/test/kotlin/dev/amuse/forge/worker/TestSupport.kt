package dev.amuse.forge.worker

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

/** A scripted Meta Model API: answers are handed out in order, every request is recorded. */
class FakeMeta(private val script: MutableList<String>) {
    val requests = mutableListOf<HttpRequestData>()
    val bodies get() = requests.map { Json.parseToJsonElement((it.body as TextContent).text) as JsonObject }

    val engine = MockEngine { request ->
        requests += request
        check(script.isNotEmpty()) { "The model was called more often than the test scripted" }
        respond(script.removeAt(0), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    fun client(effort: String? = null, temperature: Double? = null, topP: Double? = null) = MetaChatClient(
        baseUrl = "https://meta.test/v1",
        apiKey = KEY,
        model = "muse-test",
        engine = engine,
        reasoningEffort = effort,
        temperature = temperature,
        topP = topP,
        retryDelaysMs = emptyList(),
    )

    companion object {
        const val KEY = "sk-secret-test-key"
    }
}

/** One assistant message that calls the given tools: (name, JSON object text of the arguments). */
fun reply(vararg calls: Pair<String, String>): String = buildJsonObject {
    put("choices", buildJsonArray {
        add(buildJsonObject {
            put("finish_reason", "tool_calls")
            put("message", buildJsonObject {
                put("role", "assistant")
                put("tool_calls", JsonArray(calls.mapIndexed { i, (name, args) ->
                    buildJsonObject {
                        put("id", "call_$i")
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", name)
                            put("arguments", args) // a string, as the real API sends it
                        })
                    }
                }))
            })
        })
    })
}.toString()

fun textReply(text: String): String = buildJsonObject {
    put("choices", buildJsonArray {
        add(buildJsonObject {
            put("finish_reason", "stop")
            put("message", buildJsonObject { put("role", "assistant"); put("content", text) })
        })
    })
}.toString()

fun jsonArgs(vararg pairs: Pair<String, String>): String =
    JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) }).toString()

const val SCREEN_SOURCE = """import androidx.compose.runtime.Composable
import dev.ketoy.annotations.KetoyComposable
import dev.ketoy.annotations.KetoyEntryPoint

@KetoyEntryPoint
@KetoyComposable
@Composable
fun WaterTrackerScreen() {
}
"""

/** A throwaway forge directory with the files the prompt builder needs. */
fun makeForgeDir(): Path {
    val dir = Files.createTempDirectory("forge-test")
    Files.writeString(dir.resolve("AGENTS.md"), "# Test agent rules\n")
    val screens = dir.resolve(SCREENS_ROOT).resolve("dev/amuse/forge/screens")
    Files.createDirectories(screens)
    Files.writeString(screens.resolve("HelloForgeScreen.kt"), "fun HelloForgeScreen() {}\n")
    Files.writeString(dir.resolve("secret.key"), "private")
    return dir
}

/** A build runner that records calls and answers from a list; a success writes a fake bundle. */
class FakeBuild(private val results: MutableList<Boolean>, private val dir: Path) : BuildRunner {
    var calls = 0
    override fun run(forgeDir: Path): BuildResult {
        calls++
        val ok = if (results.isEmpty()) true else results.removeAt(0)
        return if (ok) {
            val bundle = dir.resolve("agent.ktx")
            Files.write(bundle, "KTX-BYTES".toByteArray())
            BuildResult(true, "BUILD SUCCESSFUL", bundle)
        } else {
            BuildResult(false, "KetoyBC: something is not allowed (build $calls)", null)
        }
    }
}
