package com.aiopenpocket.data.repository

import android.app.Application
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

data class AgentFile(val name: String, val sizeBytes: Long, val lastModified: Long)

/**
 * Read-only список файлов из общей песочницы агентов (File(filesDir,"agent")) — тот же каталог,
 * что пишет FileTools. Каталог ОБЩИЙ для всех агентов (не per-agent). Чтение напрямую по filesDir,
 * НЕ через ToolExecutor: UI-слой не ходит в agent-tool-слой.
 */
@SingleIn(AppScope::class)
class AgentFilesRepository @Inject constructor(private val application: Application) {
  private val sandboxDir: File get() = File(application.filesDir, "agent")

  fun list(): List<AgentFile> =
    sandboxDir.takeIf { it.isDirectory }
      ?.walkTopDown()
      ?.filter { it.isFile }
      ?.map { AgentFile(it.name, it.length(), it.lastModified()) }
      ?.sortedBy { it.name }
      ?.toList()
      .orEmpty()
}
