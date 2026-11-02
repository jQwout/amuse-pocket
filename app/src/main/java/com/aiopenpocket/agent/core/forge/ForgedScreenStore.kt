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

  /**
   * Crash sentinel. A bundle that compiled can still throw inside Ketoy's interpreter while it
   * composes (a range the interpreter has no method for, say), and such an exception kills the whole
   * process - Compose has no error boundary. So the section writes this flag right BEFORE the bundle
   * composes and removes it two drawn frames later (and again when the screen leaves composition). A
   * flag still present on the next load means the first render never finished: the screen is taken
   * down instead of crashing again. A crash in a later tap handler is not caught - a known gap.
   *
   * Synchronous and tiny on purpose: it must be on disk before the first composition of the bundle,
   * which is why it cannot wait for an IO coroutine.
   */
  fun markRendering(agentId: String, jobId: String) {
    runCatching {
      val dir = agentDir(agentId).also { it.mkdirs() }
      File(dir, RENDERING_FLAG).writeText(safe(jobId))
    }
  }

  /**
   * Synchronous like [markRendering]: "Update" disposes the old screen and composes the new one in
   * the same frame, and an asynchronous clear could land after the new mark and delete it.
   */
  fun clearRendering(agentId: String) {
    runCatching { File(agentDir(agentId), RENDERING_FLAG).delete() }
  }

  /** The job that was being rendered when the process last died, or null when the last render ended normally. */
  suspend fun renderingFlag(agentId: String): String? = withContext(Dispatchers.IO) {
    File(agentDir(agentId), RENDERING_FLAG).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotBlank() }
  }

  /** The person's switch "open this agent in its own interface"; a file so it goes with [clear]. */
  suspend fun isCustomUiEnabled(agentId: String): Boolean = withContext(Dispatchers.IO) {
    File(agentDir(agentId), CUSTOM_UI_FLAG).isFile
  }

  suspend fun setCustomUiEnabled(agentId: String, enabled: Boolean) = withContext(Dispatchers.IO) {
    val file = File(agentDir(agentId).also { it.mkdirs() }, CUSTOM_UI_FLAG)
    if (enabled) file.writeText("on") else file.delete()
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
    const val RENDERING_FLAG = "rendering.flag"
    const val CUSTOM_UI_FLAG = "custom_ui.flag"
    private val SAFE_ID = Regex("[A-Za-z0-9_.-]+")
  }
}
