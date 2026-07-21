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
 * Read-only список файлов ЛИЧНОЙ песочницы одного агента (`filesDir/agents/{agentId}`, Task 6) —
 * тот же per-agent каталог и тот же [sandboxRoot], что использует `FileTools`. Каждый агент видит
 * только свои файлы; старый общий `filesDir/agent` (plain chat, до Task 6) сюда не попадает и не
 * мигрируется — вне области задачи. Чтение напрямую по filesDir, НЕ через ToolExecutor: UI-слой не
 * ходит в agent-tool-слой.
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
