package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ForgedScreenStore] on the real (Robolectric) `filesDir`: what survives a restart is exactly what
 * `save` wrote, a damaged file must not crash the agent's card, and one agent's folder must never leak
 * into another's. Every state carries tokens only this test invents (job ids, entry points), so a
 * round-trip that dropped or swapped a field cannot pass on defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ForgedScreenStoreTest {

  private val application = RuntimeEnvironment.getApplication()
  private val store = ForgedScreenStore(application)

  private fun agentDir(agentId: String) = File(File(application.filesDir, ForgedScreenStore.DIR_NAME), agentId)

  private val everyState: List<ForgedScreenState> = listOf(
    ForgedScreenState.Requested(jobId = "job-req-1", request = "water tracker", previousJobId = "job-old-0"),
    ForgedScreenState.Requested(jobId = "job-req-2", request = "no previous"),
    ForgedScreenState.NeedsInfo(jobId = "job-ask-3", request = "budget screen", question = "Which currency?"),
    ForgedScreenState.Working(jobId = "job-work-4", request = "habit grid", message = "compiling"),
    ForgedScreenState.Working(jobId = "job-work-5", request = "habit grid"),
    ForgedScreenState.Ready(
      jobId = "job-ready-6",
      request = "timer",
      entryPoint = "TimerScreenEntry",
      version = 7,
      fileName = "job-ready-6.ktx",
    ),
    ForgedScreenState.Failed(jobId = "job-fail-7", request = "chart", message = "compile error"),
    ForgedScreenState.Failed(jobId = null, request = "chart", message = "no jobId yet"),
  )

  @Test
  fun `every state survives a save and a load unchanged`() = runTest {
    for ((index, state) in everyState.withIndex()) {
      val agent = "agent-$index"
      store.save(agent, state)
      assertEquals("state #$index", state, store.load(agent))
    }
  }

  @Test
  fun `a state with no file loads as None`() = runTest {
    assertEquals(ForgedScreenState.None, store.load("never-saved"))
  }

  @Test
  fun `saving None deletes the file so the agent starts clean`() = runTest {
    store.save("a1", ForgedScreenState.Working("job-x", "req"))
    assertTrue(File(agentDir("a1"), ForgedScreenStore.STATE_FILE).isFile)

    store.save("a1", ForgedScreenState.None)

    assertFalse(File(agentDir("a1"), ForgedScreenStore.STATE_FILE).exists())
    assertEquals(ForgedScreenState.None, store.load("a1"))
  }

  @Test
  fun `a corrupt state file reads as None instead of crashing`() = runTest {
    store.save("a1", ForgedScreenState.Working("job-x", "req"))
    val file = File(agentDir("a1"), ForgedScreenStore.STATE_FILE)

    file.writeText("{ this is not json")
    assertEquals(ForgedScreenState.None, store.load("a1"))

    // Valid JSON, unknown state discriminator: the same answer (an older/newer build wrote it).
    file.writeText("""{"type":"teleporting","jobId":"j"}""")
    assertEquals(ForgedScreenState.None, store.load("a1"))

    file.writeText("")
    assertEquals(ForgedScreenState.None, store.load("a1"))
  }

  @Test
  fun `a save leaves no temp file behind and overwrites the previous state`() = runTest {
    store.save("a1", ForgedScreenState.Requested("job-1", "first"))
    store.save("a1", ForgedScreenState.Requested("job-2", "second"))

    assertEquals(ForgedScreenState.Requested("job-2", "second"), store.load("a1"))
    assertEquals(listOf(ForgedScreenStore.STATE_FILE), agentDir("a1").list()!!.toList())
  }

  @Test
  fun `a bundle round-trips byte for byte and the name is the one Ready keeps`() = runTest {
    val bytes = ByteArray(300) { (it * 7).toByte() } // includes negative bytes: no text decoding on the way

    val name = store.writeBundle("a1", "job-9", bytes)

    assertEquals("job-9.ktx", name)
    assertArrayEquals(bytes, store.readBundle("a1", name))
    assertNull(store.readBundle("a1", "job-missing.ktx"))
    assertEquals(listOf("job-9.ktx"), agentDir("a1").list()!!.toList())
  }

  @Test
  fun `a second bundle with the same job id replaces the first`() = runTest {
    store.writeBundle("a1", "job-9", byteArrayOf(1, 2, 3))
    val name = store.writeBundle("a1", "job-9", byteArrayOf(9, 9))

    assertArrayEquals(byteArrayOf(9, 9), store.readBundle("a1", name))
  }

  @Test
  fun `clear removes the whole folder, state and bundles`() = runTest {
    store.save("a1", ForgedScreenState.Working("job-x", "req"))
    store.writeBundle("a1", "job-x", byteArrayOf(1))

    store.clear("a1")

    assertFalse(agentDir("a1").exists())
    assertEquals(ForgedScreenState.None, store.load("a1"))
    assertNull(store.readBundle("a1", "job-x.ktx"))
  }

  @Test
  fun `clearing an agent that has nothing is not an error`() = runTest {
    store.clear("nobody")
    assertEquals(ForgedScreenState.None, store.load("nobody"))
  }

  @Test
  fun `two agents are isolated from each other`() = runTest {
    store.save("alpha", ForgedScreenState.Working("job-alpha", "a"))
    store.save("beta", ForgedScreenState.Failed("job-beta", "b", "boom"))
    store.writeBundle("alpha", "job-alpha", byteArrayOf(1, 1))
    store.writeBundle("beta", "job-beta", byteArrayOf(2, 2))

    store.clear("alpha")

    assertEquals(ForgedScreenState.None, store.load("alpha"))
    assertEquals(ForgedScreenState.Failed("job-beta", "b", "boom"), store.load("beta"))
    assertArrayEquals(byteArrayOf(2, 2), store.readBundle("beta", "job-beta.ktx"))
    // A bundle written for one agent is invisible under the other's folder.
    assertNull(store.readBundle("alpha", "job-beta.ktx"))
  }

  @Test
  fun `an id with a path separator is rejected by every entry point`() {
    val state = ForgedScreenState.Working("job-x", "req")

    assertRejected { store.save("../x", state) }
    assertRejected { store.load("../x") }
    assertRejected { store.clear("../x") }
    assertRejected { store.writeBundle("a1", "../x", byteArrayOf(1)) }
    assertRejected { store.readBundle("a1", "../x.ktx") }
    assertRejected { store.save("a/b", state) }
    assertRejected { store.save("", state) }
    assertRejected { store.save("a\\b", state) }
    // Nothing was created outside (or inside) the forged-screens folder.
    assertFalse(File(application.filesDir, "x").exists())
    val created = File(application.filesDir, ForgedScreenStore.DIR_NAME).list()?.toSet().orEmpty()
    assertTrue(created.toString(), created.all { it == "a1" })
  }

  /**
   * "." and ".." match the character class but resolve to the folder itself or its parent: `clear("..")`
   * would delete the whole `filesDir`.
   */
  @Test
  fun `dot and dot-dot are not valid ids`() {
    File(application.filesDir, "canary.txt").writeText("keep me")

    assertRejected { store.clear("..") }
    assertRejected { store.clear(".") }

    assertTrue(File(application.filesDir, "canary.txt").isFile)
  }

  private fun assertRejected(block: suspend () -> Unit) {
    assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { block() } }
  }

  @Test
  fun `the rendering flag survives until cleared and names the job`() = runTest {
    assertNull(store.renderingFlag("agent-a"))
    store.markRendering("agent-a", "job-77")
    assertEquals("job-77", store.renderingFlag("agent-a"))
    assertNull(store.renderingFlag("agent-b"))
    store.clearRendering("agent-a")
    assertNull(store.renderingFlag("agent-a"))
    // clear() takes the flag down with everything else.
    store.markRendering("agent-a", "job-78")
    store.clear("agent-a")
    assertNull(store.renderingFlag("agent-a"))
  }

}
