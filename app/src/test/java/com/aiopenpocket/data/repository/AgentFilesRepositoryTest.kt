package com.aiopenpocket.data.repository

// @trace user-agent-builder-848153

import android.app.Application
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AgentFilesRepository] now reads a specific agent's OWN sandbox (`filesDir/agents/{agentId}`,
 * Task 6) rather than one shared directory — proves `list(agentId)` only ever sees that agent's
 * files, never a sibling agent's, mirroring the isolation [FileToolsSandboxTest] proves for the
 * tool side of the same sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentFilesRepositoryTest {

  private val application: Application = RuntimeEnvironment.getApplication()

  private fun agentDir(agentId: String): File =
    File(File(application.filesDir, "agents"), agentId).apply { mkdirs() }

  @Test
  fun `list returns files from that agent's own sandbox sorted by name`() = runTest {
    val sandboxDir = agentDir("budget")
    File(sandboxDir, "b.txt").writeText("second")
    File(sandboxDir, "a.txt").writeText("first")

    val repo = AgentFilesRepository(application)
    val files = repo.list("budget")

    assertEquals(2, files.size)
    assertEquals(listOf("a.txt", "b.txt"), files.map { it.name })
    assertEquals("first".toByteArray().size.toLong(), files[0].sizeBytes)
    assertEquals("second".toByteArray().size.toLong(), files[1].sizeBytes)
  }

  @Test
  fun `list returns empty list when that agent's sandbox dir is missing`() = runTest {
    val sandboxDir = File(File(application.filesDir, "agents"), "budget")
    assertTrue(!sandboxDir.exists())

    val repo = AgentFilesRepository(application)

    assertEquals(emptyList<AgentFile>(), repo.list("budget"))
  }

  @Test
  fun `list never returns another agent's files`() = runTest {
    File(agentDir("budget"), "report.txt").writeText("budget-data")
    File(agentDir("diary"), "entry.txt").writeText("diary-data")

    val repo = AgentFilesRepository(application)
    val budgetFiles = repo.list("budget")

    assertEquals(listOf("report.txt"), budgetFiles.map { it.name })
  }

  @Test
  fun `list does not read the legacy shared plain-chat sandbox`() = runTest {
    val legacySharedDir = File(application.filesDir, "agent").apply { mkdirs() }
    File(legacySharedDir, "old.txt").writeText("legacy-plain-chat-file")
    agentDir("budget") // exists but empty

    val repo = AgentFilesRepository(application)

    assertEquals(emptyList<AgentFile>(), repo.list("budget"))
  }

  @Test
  fun `list throws instead of walking outside the sandbox when agentId fails the id-format check`() {
    // Today's only caller (AgentInspectorViewModel) always passes an already-validated
    // AgentDefinition.id, but sandboxRoot() must fail closed for any future caller that doesn't -
    // "../evil" both fails AgentValidator.ID_REGEX and would, if resolved, walk a directory outside
    // filesDir/agents entirely.
    val repo = AgentFilesRepository(application)

    assertThrows(SecurityException::class.java) {
      runBlocking { repo.list("../evil") }
    }
  }
}
