package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153

import android.app.Application
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentMemoryPolicy
import com.aiopenpocket.agent.core.agent.AgentSource
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.mcp.ToolResult
import com.aiopenpocket.agent.loop.AgentMode
import com.aiopenpocket.agent.loop.TurnContextHolder
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Security regression for the per-agent file sandbox (Task 6 of the user-agent-builder plan): a
 * bound custom agent's `file_tool` calls must be confined to `filesDir/agents/{id}` IN CODE — a
 * system prompt saying "stay in your sandbox" doesn't stop a model from trying `../other-agent/...`,
 * an absolute path, mixed `\`/`/` separators, or a canonical up-and-back path, so the containment
 * check has to live in [FileTools]/[sandboxRoot], not in prompt wording. Plain chat (no bound
 * agent) must keep the exact `filesDir/agent` shared root it always had — the one path this task
 * must NOT touch.
 *
 * Runs against a real Robolectric `filesDir` (like [com.aiopenpocket.data.repository.AgentFilesRepositoryTest])
 * rather than a fake, so containment is proven against the actual filesystem/canonical-path
 * behavior, not a hand-rolled stand-in for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FileToolsSandboxTest {

  private lateinit var application: Application
  private lateinit var holder: TurnContextHolder
  private lateinit var tool: FileTools

  @Before
  fun setUp() {
    application = RuntimeEnvironment.getApplication()
    holder = TurnContextHolder()
    tool = FileTools(application, holder)
  }

  private fun agent(id: String) = AgentDefinition(
    id = id,
    source = AgentSource.USER,
    name = "Тестовый агент $id",
    description = "Кастомный агент для проверки изоляции файловой песочницы.",
    iconKey = "sparkles",
    systemPrompt = "Ты — тестовый агент для unit-тестов.",
    toolNames = setOf("file_tool"),
    skillIds = emptySet(),
    defaultMode = AgentMode.CHAT,
    allowedModes = setOf(AgentMode.CHAT),
    memoryPolicy = AgentMemoryPolicy(namespace = "agent:$id:", includeSharedProfile = false),
    allowedWidgetTemplates = emptySet(),
    configVersion = 1L,
    status = AgentStatus.PUBLISHED,
  )

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  private fun readFile(path: String) = args("action" to "read_file", "path" to path)
  private fun writeFile(path: String, content: String) =
    args("action" to "write_file", "path" to path, "content" to content)
  private fun listDir(path: String = ".") = args("action" to "list_dir", "path" to path)

  /** Seeds `agents/{agentId}/{fileName}` directly on disk, bypassing the tool entirely. */
  private fun seedAgentFile(agentId: String, fileName: String, content: String): File {
    val dir = File(File(application.filesDir, "agents"), agentId).apply { mkdirs() }
    return File(dir, fileName).apply { writeText(content) }
  }

  @Test
  fun `agent A's sandbox root is separate from agent B's - A does not see B's file by default`() = runTest {
    seedAgentFile("agent-b", "private.txt", "b-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(readFile("private.txt"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
  }

  @Test
  fun `agent A reads its own file normally`() = runTest {
    seedAgentFile("agent-a", "private.txt", "a-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(readFile("private.txt"))

    assertEquals(ToolResult.Success("a-secret"), result)
  }

  @Test
  fun `agent A cannot reach agent B's file via relative traversal`() = runTest {
    seedAgentFile("agent-b", "private.txt", "b-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(readFile("../agent-b/private.txt"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse(result.toString().contains("b-secret"))
  }

  @Test
  fun `agent A cannot reach agent B's file via an absolute path`() = runTest {
    val bFile = seedAgentFile("agent-b", "private.txt", "b-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(readFile(bFile.absolutePath))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse(result.toString().contains("b-secret"))
  }

  @Test
  fun `agent A cannot reach agent B's file via mixed backslash slash separators`() = runTest {
    seedAgentFile("agent-b", "private.txt", "b-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(readFile("..\\agent-b/private.txt"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse(result.toString().contains("b-secret"))
  }

  @Test
  fun `agent A cannot reach agent B's file via canonical up-and-back traversal`() = runTest {
    seedAgentFile("agent-b", "private.txt", "b-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(readFile("agent-a/../../agent-b/private.txt"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse(result.toString().contains("b-secret"))
  }

  @Test
  fun `agent A cannot list agent B's directory via traversal`() = runTest {
    seedAgentFile("agent-b", "private.txt", "b-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(listDir("../agent-b"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse(result.toString().contains("private.txt"))
  }

  @Test
  fun `agent A's list_dir shows only its own files, never agent B's`() = runTest {
    seedAgentFile("agent-a", "mine.txt", "a-data")
    seedAgentFile("agent-b", "private.txt", "b-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(listDir())

    val text = result.toString()
    assertTrue(text.contains("mine.txt"))
    assertFalse(text.contains("private.txt"))
  }

  @Test
  fun `agent A's writes cannot escape its own sandbox via a traversal path`() = runTest {
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(writeFile("../escape.txt", "pwned"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    val escapedIntoAgentsRoot = File(File(application.filesDir, "agents"), "escape.txt")
    assertFalse(escapedIntoAgentsRoot.exists())
  }

  @Test
  fun `agent A cannot write into agent B's directory via an absolute path`() = runTest {
    val bDir = seedAgentFile("agent-b", "existing.txt", "b-data").parentFile!!
    holder.set("conv-a", agent("agent-a"))
    val bTarget = File(bDir, "pwned.txt")

    val result = tool.execute(writeFile(bTarget.absolutePath, "pwned"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse(bTarget.exists())
  }

  /**
   * An absolute path into ITS OWN sandbox writes exactly to that file instead of being re-rooted inside as
   * nested folders. Only on Linux (as on Android): on a Windows JVM an absolute path carries '\' and is rejected.
   */
  @Test
  fun `an absolute path into the agent's own sandbox writes exactly there`() = runTest {
    org.junit.Assume.assumeTrue(File.separatorChar == '/')
    holder.set("conv-a", agent("agent-a"))
    val own = File(File(File(application.filesDir, "agents"), "agent-a"), "notes.txt")

    val result = tool.execute(writeFile(own.absolutePath, "mine"))

    assertTrue("expected Success, got $result", result is ToolResult.Success)
    assertEquals("mine", own.readText())
  }

  /** A short "/notes.txt" — a habit of models — still counts from the sandbox root. */
  @Test
  fun `a short leading-slash path still counts from the sandbox root`() = runTest {
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(writeFile("/notes.txt", "mine"))

    assertTrue("expected Success, got $result", result is ToolResult.Success)
    assertEquals("mine", File(File(File(application.filesDir, "agents"), "agent-a"), "notes.txt").readText())
  }

  @Test
  fun `agent A cannot write into agent B's directory via mixed backslash slash separators`() = runTest {
    seedAgentFile("agent-b", "existing.txt", "b-data")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(writeFile("..\\agent-b/pwned.txt", "pwned"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    val target = File(File(File(application.filesDir, "agents"), "agent-b"), "pwned.txt")
    assertFalse(target.exists())
  }

  @Test
  fun `agent A cannot write into agent B's directory via canonical up-and-back traversal`() = runTest {
    seedAgentFile("agent-b", "existing.txt", "b-data")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(writeFile("agent-a/../../agent-b/pwned.txt", "pwned"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    val target = File(File(File(application.filesDir, "agents"), "agent-b"), "pwned.txt")
    assertFalse(target.exists())
  }

  @Test
  fun `plain chat keeps the exact shared filesDir slash agent sandbox`() = runTest {
    holder.set("conv1", null)

    tool.execute(writeFile("shared.txt", "plain-chat-data"))

    val sharedFile = File(File(application.filesDir, "agent"), "shared.txt")
    assertTrue(sharedFile.exists())
    assertEquals("plain-chat-data", sharedFile.readText())
  }

  @Test
  fun `custom agent cannot reach the plain-chat shared sandbox via traversal`() = runTest {
    // filesDir/agent (plain chat's shared root) is a sibling of filesDir/agents, two levels up
    // from filesDir/agents/agent-a - same shape as the agent-to-agent escape, against the legacy
    // root.
    // Filename and content deliberately DON'T share a substring: the rejection message legitimately
    // echoes back the attempted PATH (which contains the filename), so asserting against the
    // file's CONTENT (not its name) is what actually proves nothing was read.
    val sharedDir = File(application.filesDir, "agent").apply { mkdirs() }
    File(sharedDir, "legacy-plain-chat.txt").writeText("shared-secret")
    holder.set("conv-a", agent("agent-a"))

    val result = tool.execute(readFile("../../agent/legacy-plain-chat.txt"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse(result.toString().contains("shared-secret"))
  }
}
