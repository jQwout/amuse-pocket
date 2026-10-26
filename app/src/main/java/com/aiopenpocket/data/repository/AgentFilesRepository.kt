package com.aiopenpocket.data.repository

// @trace user-agent-builder-848153

import android.app.Application
import com.aiopenpocket.agent.tools.sandboxRoot
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class AgentFile(val name: String, val sizeBytes: Long, val lastModified: Long)

/**
 * A read-only list of files in one agent's PRIVATE sandbox (`filesDir/agents/{agentId}`, Task 6) — the
 * same per-agent directory and the same [sandboxRoot] that `FileTools` uses. Each agent sees only its
 * own files; the old shared `filesDir/agent` (plain chat, before Task 6) does not get here and is not
 * migrated — outside the task's scope. Read directly from filesDir, NOT through ToolExecutor: the UI
 * layer does not call into the agent tool layer.
 */
@SingleIn(AppScope::class)
@Inject
class AgentFilesRepository(private val application: Application) {

  suspend fun list(agentId: String): List<AgentFile> = withContext(Dispatchers.IO) {
    val sandboxDir: File = sandboxRoot(application.filesDir, agentId)
    sandboxDir.takeIf { it.isDirectory }
      ?.walkTopDown()
      ?.filter { it.isFile }
      ?.map { AgentFile(it.name, it.length(), it.lastModified()) }
      ?.sortedBy { it.name }
      ?.toList()
      .orEmpty()
  }
}
