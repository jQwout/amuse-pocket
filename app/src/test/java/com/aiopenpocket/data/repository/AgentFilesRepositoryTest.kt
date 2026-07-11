package com.aiopenpocket.data.repository

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentFilesRepositoryTest {

  private val application: Application = RuntimeEnvironment.getApplication()

  @Test
  fun `list returns files from agent sandbox sorted by name`() {
    val sandboxDir = File(application.filesDir, "agent").apply { mkdirs() }
    File(sandboxDir, "b.txt").writeText("second")
    File(sandboxDir, "a.txt").writeText("first")

    val repo = AgentFilesRepository(application)
    val files = repo.list()

    assertEquals(2, files.size)
    assertEquals(listOf("a.txt", "b.txt"), files.map { it.name })
    assertEquals("first".toByteArray().size.toLong(), files[0].sizeBytes)
    assertEquals("second".toByteArray().size.toLong(), files[1].sizeBytes)
  }

  @Test
  fun `list returns empty list when sandbox dir is missing`() {
    val sandboxDir = File(application.filesDir, "agent")
    assertTrue(!sandboxDir.exists())

    val repo = AgentFilesRepository(application)

    assertEquals(emptyList<AgentFile>(), repo.list())
  }
}
