package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153

import android.app.Application
import com.aiopenpocket.agent.core.mcp.ToolResult
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Security regression for [ExternalFilesTool]'s URI gate: the `uri` argument is written by the MODEL,
 * so it is untrusted input, and `ContentResolver` accepts `file://` as readily as `content://`. Until
 * this gate existed, an agent could hand the tool `file:///data/data/{pkg}/databases/...` and read the
 * app's own private storage — walking straight around [FileTools]' canonical-path sandbox AND around
 * [DatabaseQueryTool]'s table allowlist (which exists precisely to keep `user_agents`, `messages` and
 * `facts` unreachable). The containment therefore has to live in the tool, not in prompt wording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExternalFilesToolUriGateTest {

  private lateinit var application: Application
  private lateinit var tool: ExternalFilesTool

  @Before
  fun setUp() {
    application = RuntimeEnvironment.getApplication()
    tool = ExternalFilesTool(application)
  }

  private fun args(vararg pairs: Pair<String, String>): Map<String, JsonElement> =
    pairs.associate { it.first to JsonPrimitive(it.second) }

  /** A real file inside the app's own private data dir — what the bypass was actually reaching. */
  private fun seedPrivateFile(name: String, content: String): File =
    File(application.filesDir, name).apply { writeText(content) }

  @Test
  fun `read_file rejects a file scheme URI pointing into the app's own private storage`() = runTest {
    val secret = seedPrivateFile("private.txt", "app-private-secret")

    val result = tool.execute(args("action" to "read_file", "uri" to "file://${secret.absolutePath}"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse("private content leaked: $result", result.toString().contains("app-private-secret"))
  }

  @Test
  fun `read_file rejects a bare filesystem path with no scheme at all`() = runTest {
    val secret = seedPrivateFile("bare.txt", "bare-path-secret")

    val result = tool.execute(args("action" to "read_file", "uri" to secret.absolutePath))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertFalse("private content leaked: $result", result.toString().contains("bare-path-secret"))
  }

  @Test
  fun `write_file rejects a file scheme URI instead of overwriting private storage`() = runTest {
    val victim = seedPrivateFile("victim.txt", "original-content")

    val result = tool.execute(
      args("action" to "write_file", "uri" to "file://${victim.absolutePath}", "content" to "pwned"),
    )

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertTrue("file was overwritten despite the rejection", victim.readText() == "original-content")
  }

  @Test
  fun `list_dir rejects a file scheme URI`() = runTest {
    val result = tool.execute(args("action" to "list_dir", "uri" to "file://${application.filesDir.absolutePath}"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
  }

  @Test
  fun `a content scheme URI with no persisted grant is refused, not passed to the resolver`() = runTest {
    // No SAF picker has ever granted this app anything, so no content:// URI is legitimately
    // reachable either — fail closed rather than letting the resolver decide.
    val result = tool.execute(
      args("action" to "read_file", "uri" to "content://com.android.providers.downloads.documents/document/42"),
    )

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
  }

  @Test
  fun `the rejection names the scheme so the model can correct itself`() = runTest {
    val result = tool.execute(args("action" to "read_file", "uri" to "file:///etc/hosts"))

    assertTrue("expected an Error, got $result", result is ToolResult.Error)
    assertTrue("unhelpful rejection message: $result", result.toString().contains("content://"))
  }
}
