package com.aiopenpocket.agent.core.forge

// @trace cloud-forged-screens-40d702

import android.app.Application
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-agent files under `filesDir/forged_screens/<agentId>/`: `state.json` and one `<jobId>.ktx`
 * per bundle. Files rather than Room on purpose: before the first release a schema change means a
 * destructive migration that wipes the owner's phone, and a bundle is a blob anyway.
 *
 * `filesDir`, not `cacheDir`: a forged screen is the agent's, not a cache the system may clear.
 * Writes go through a temp file and a rename so a crash mid-write leaves the previous state intact.
 */
@SingleIn(AppScope::class)
@Inject
class ForgedScreenStore(private val application: Application) {

  private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

  suspend fun load(agentId: String): ForgedScreenState = withContext(Dispatchers.IO) {
    val file = stateFile(agentId)
    if (!file.isFile) return@withContext ForgedScreenState.None
    runCatching { json.decodeFromString<ForgedScreenState>(file.readText()) }
      .getOrElse { ForgedScreenState.None }
  }

  suspend fun save(agentId: String, state: ForgedScreenState) = withContext(Dispatchers.IO) {
    val dir = agentDir(agentId).also { it.mkdirs() }
    if (state is ForgedScreenState.None) {
      stateFile(agentId).delete()
      return@withContext
    }
    val tmp = File(dir, "state.json.tmp")
    tmp.writeText(json.encodeToString(state))
    val target = stateFile(agentId)
    if (!tmp.renameTo(target)) {
      target.writeText(tmp.readText())
      tmp.delete()
    }
  }

  /** Stores the bundle and returns its file name, the value [ForgedScreenState.Ready.fileName] keeps. */
  suspend fun writeBundle(agentId: String, jobId: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
    val dir = agentDir(agentId).also { it.mkdirs() }
    val name = "${safe(jobId)}.ktx"
    val tmp = File(dir, "$name.tmp")
    tmp.writeBytes(bytes)
    val target = File(dir, name)
    if (!tmp.renameTo(target)) {
      target.writeBytes(tmp.readBytes())
      tmp.delete()
    }
    name
  }

  suspend fun readBundle(agentId: String, fileName: String): ByteArray? = withContext(Dispatchers.IO) {
    File(agentDir(agentId), safe(fileName)).takeIf { it.isFile }?.readBytes()
  }

  /** Forgets everything about this agent's screen, bundles included. */
  suspend fun clear(agentId: String) = withContext(Dispatchers.IO) {
    agentDir(agentId).deleteRecursively()
    Unit
  }

  private fun stateFile(agentId: String) = File(agentDir(agentId), STATE_FILE)

  private fun agentDir(agentId: String) = File(File(application.filesDir, DIR_NAME), safe(agentId))

  /** Ids are ours, but a path separator in one must never become a path. */
  private fun safe(id: String): String {
    require(id.isNotBlank() && SAFE_ID.matches(id) && id != "." && id != "..") { "Unsafe id for a file name: $id" }
    return id
  }

  companion object {
    const val DIR_NAME = "forged_screens"
    const val STATE_FILE = "state.json"
    private val SAFE_ID = Regex("[A-Za-z0-9_.-]+")
  }
}
