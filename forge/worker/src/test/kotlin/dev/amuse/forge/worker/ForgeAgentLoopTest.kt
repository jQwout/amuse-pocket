package dev.amuse.forge.worker

import dev.amuse.forge.contract.Exchange
import dev.amuse.forge.contract.WorkerJob
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ForgeAgentLoopTest {
    private val job = WorkerJob(
        jobId = "j1", agentId = "a1", agentName = "Water", agentDescription = "Tracks water",
        request = "a water tracker with 8 glasses and a reset button", version = 1,
    )

    private val write = "write_screen" to jsonArgs("name" to "WaterTrackerScreen", "source" to SCREEN_SOURCE)
    private val build = "build_bundle" to "{}"
    private val finishDone = "finish" to jsonArgs("status" to "done")

    private class Rig(val loop: ForgeAgentLoop, val build: FakeBuild, val dir: Path)

    private fun rig(
        meta: FakeMeta,
        builds: List<Boolean> = emptyList(),
        maxCalls: Int = 25,
        timeoutMs: Long = 900_000,
        clock: () -> Long = System::currentTimeMillis,
    ): Rig {
        val dir = makeForgeDir()
        val fake = FakeBuild(builds.toMutableList(), dir)
        return Rig(ForgeAgentLoop(meta.client(), dir, fake, maxModelCalls = maxCalls, timeoutMs = timeoutMs, clock = clock), fake, dir)
    }

    private fun toolResults(meta: FakeMeta): List<String> =
        meta.bodies.last()["messages"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "tool" }
            .map { it["content"]!!.jsonPrimitive.content }

    @Test
    fun `happy path writes builds finishes and returns the bundle bytes`() = runTest {
        val meta = FakeMeta(mutableListOf(reply(write), reply(build), reply(finishDone)))
        val rig = rig(meta)

        val outcome = rig.loop.run(job)

        val done = assertIs<ForgeOutcome.Done>(outcome)
        assertEquals("WaterTrackerScreen", done.entryPoint)
        assertEquals("KTX-BYTES", String(done.bundleBytes))
        assertContains(done.source, "fun WaterTrackerScreen(")
        assertContains(done.source, "package dev.amuse.forge.screens.generated")
        assertEquals(1, rig.build.calls)
        assertTrue(Files.exists(rig.dir.resolve("$GENERATED_DIR/WaterTrackerScreen.kt")))
        assertEquals(3, meta.requests.size)
    }

    @Test
    fun `tool results travel back to the model as tool messages`() = runTest {
        val meta = FakeMeta(mutableListOf(reply(write), reply(build), reply(finishDone)))
        rig(meta).loop.run(job)

        val results = toolResults(meta)
        assertEquals(2, results.size)
        assertContains(results[1], "\"ok\":true")
        val ids = meta.bodies.last()["messages"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "tool" }.map { it["tool_call_id"]!!.jsonPrimitive.content }
        assertEquals(listOf("call_0", "call_0"), ids)
    }

    @Test
    fun `the first request carries the job context and the earlier answers`() = runTest {
        val meta = FakeMeta(mutableListOf(reply("finish" to jsonArgs("status" to "failed", "notes" to "no"))))
        val withContext = job.copy(
            previousSource = "fun OldScreen() {}", previousEntryPoint = "OldScreen",
            exchanges = listOf(Exchange("Which unit?", "Millilitres")),
        )
        rig(meta).loop.run(withContext)

        val messages = meta.bodies.first()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("system", messages[0]["role"]!!.jsonPrimitive.content)
        val system = messages[0]["content"]!!.jsonPrimitive.content
        assertContains(system, "Test agent rules") // AGENTS.md
        assertContains(system, "HelloForgeScreen") // the template
        assertContains(system, "Allowed building blocks") // the catalog
        val user = messages[1]["content"]!!.jsonPrimitive.content
        assertContains(user, "water tracker")
        assertContains(user, "UPDATE")
        assertContains(user, "fun OldScreen() {}")
        assertContains(user, "Millilitres")
    }

    @Test
    fun `needs_info ends the job with the question`() = runTest {
        val meta = FakeMeta(mutableListOf(reply("finish" to jsonArgs("status" to "needs_info", "question" to "Which units?"))))

        val outcome = rig(meta).loop.run(job)

        assertEquals(ForgeOutcome.NeedsInfo("Which units?"), outcome)
    }

    @Test
    fun `needs_info without a question is rejected and the loop goes on`() = runTest {
        val meta = FakeMeta(mutableListOf(
            reply("finish" to jsonArgs("status" to "needs_info")),
            reply("finish" to jsonArgs("status" to "needs_info", "question" to "What?")),
        ))

        val outcome = rig(meta).loop.run(job)

        assertEquals(ForgeOutcome.NeedsInfo("What?"), outcome)
        assertContains(toolResults(meta).first(), "non-empty")
    }

    @Test
    fun `finish done without a build is rejected and the loop continues`() = runTest {
        val meta = FakeMeta(mutableListOf(reply(write), reply(finishDone), reply(build), reply(finishDone)))
        val rig = rig(meta)

        val outcome = rig.loop.run(job)

        assertIs<ForgeOutcome.Done>(outcome)
        assertEquals(1, rig.build.calls)
        assertEquals(4, meta.requests.size, "the rejected finish must cost a model call, not end the job")
        assertContains(toolResults(meta)[1], "rejected")
    }

    @Test
    fun `a write after a good build invalidates it`() = runTest {
        val meta = FakeMeta(mutableListOf(reply(write), reply(build), reply(write), reply(finishDone), reply(build), reply(finishDone)))
        val rig = rig(meta)

        val outcome = rig.loop.run(job)

        assertIs<ForgeOutcome.Done>(outcome)
        assertEquals(2, rig.build.calls)
        assertEquals(6, meta.requests.size)
    }

    @Test
    fun `three failed builds in a row end the job as failed with the log tail`() = runTest {
        val meta = FakeMeta(mutableListOf(reply(write), reply(build), reply(build), reply(build), reply(build)))
        val rig = rig(meta, builds = listOf(false, false, false, false))

        val outcome = rig.loop.run(job)

        val failed = assertIs<ForgeOutcome.Failed>(outcome)
        assertEquals(3, rig.build.calls)
        assertEquals(4, meta.requests.size, "the loop stops right after the third failed build")
        assertContains(failed.message, "did not compile")
        assertContains(failed.message, "KetoyBC: something is not allowed (build 3)")
    }

    @Test
    fun `a new write_screen resets the failure count`() = runTest {
        val meta = FakeMeta(mutableListOf(
            reply(write), reply(build), reply(build),
            reply(write), reply(build), reply(build),
            reply(write), reply(build), reply(finishDone),
        ))
        val rig = rig(meta, builds = listOf(false, false, false, false, true))

        assertIs<ForgeOutcome.Done>(rig.loop.run(job))
    }

    @Test
    fun `the step limit ends the job as failed`() = runTest {
        val meta = FakeMeta(MutableList(10) { reply("list_files" to "{}") })

        val outcome = rig(meta, maxCalls = 4).loop.run(job)

        assertContains(assertIs<ForgeOutcome.Failed>(outcome).message, "all 4 steps")
        assertEquals(4, meta.requests.size)
    }

    @Test
    fun `the time limit ends the job as failed`() = runTest {
        var now = 0L
        val meta = FakeMeta(mutableListOf(reply("list_files" to "{}"), reply("list_files" to "{}")))

        val outcome = rig(meta, timeoutMs = 1_000, clock = { now.also { now += 600 } }).loop.run(job)

        assertContains(assertIs<ForgeOutcome.Failed>(outcome).message, "ran out of time")
    }

    @Test
    fun `replies without tool calls are nudged and then give up`() = runTest {
        val meta = FakeMeta(mutableListOf(textReply("I will do it"), textReply("Sure"), textReply("Really")))

        val outcome = rig(meta).loop.run(job)

        assertContains(assertIs<ForgeOutcome.Failed>(outcome).message, "stopped calling tools")
        assertEquals(3, meta.requests.size)
    }

    @Test
    fun `unknown tools and broken arguments come back as errors the model can read`() = runTest {
        val meta = FakeMeta(mutableListOf(
            reply("rm_rf" to "{}", "read_file" to "not json"),
            reply("finish" to jsonArgs("status" to "failed", "notes" to "gave up")),
        ))

        val outcome = rig(meta).loop.run(job)

        assertEquals("gave up", assertIs<ForgeOutcome.Failed>(outcome).message)
        val results = toolResults(meta)
        assertContains(results[0], "unknown tool")
        assertContains(results[1], "not valid JSON")
    }

    @Test
    fun `a model service error becomes a failed outcome without the key`() = runTest {
        val engine = MockEngine { respond("denied for key ${FakeMeta.KEY}", HttpStatusCode.Unauthorized) }
        val client = MetaChatClient("https://meta.test/v1", FakeMeta.KEY, "m", engine, retryDelaysMs = emptyList())
        val dir = makeForgeDir()

        val outcome = ForgeAgentLoop(client, dir, FakeBuild(mutableListOf(), dir)).run(job)

        val message = assertIs<ForgeOutcome.Failed>(outcome).message
        assertContains(message, "401")
        assertFalse(message.contains(FakeMeta.KEY))
    }

    @Test
    fun `progress is reported for writes and builds`() = runTest {
        val meta = FakeMeta(mutableListOf(reply(write), reply(build), reply(finishDone)))
        val seen = mutableListOf<String>()

        rig(meta).loop.run(job) { seen += it }

        assertEquals(listOf("Writing the screen", "Compiling the screen"), seen)
    }
}
