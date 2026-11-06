package com.aiopenpocket.agent.core.forge

// @trace screen-brief-and-mockup-before-the-forge-builds-82cfb2

import android.app.Application
import android.util.Log
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * A picture of the screen before it is built: Muse Image draws the [ScreenBrief] as a phone UI mockup in
 * the app's palette, so the person sees what they are asking for and can change the words before the forge
 * spends minutes on code. One cent a drawing, on the person's own Meta key.
 *
 * The picture is a sketch for the person, not a contract: the forge builds from the brief's text. The file
 * is one per agent in `filesDir/forge_mockups`, replaced by the next drawing and deleted by [discard].
 */
@Inject
@SingleIn(AppScope::class)
class ScreenMockupGenerator(
  private val application: Application,
  private val client: ImageGenClient,
) {

  fun isConfigured(): Boolean = client.isConfigured()

  /** The mockup file, or null when nothing was drawn (no key, a refusal, the network). */
  suspend fun draw(agentId: String, agentName: String, brief: ScreenBrief, dark: Boolean): File? =
    withContext(Dispatchers.IO) {
      val prompt = prompt(agentName, brief, dark)
      // Portrait first. Only if Meta turns that size down is the default frame tried: on a network failure
      // or a content refusal a second request would just repeat the wait (each one already retries).
      val portrait = client.attempt(prompt, ImageGenClient.PORTRAIT_SIZE)
      val image = portrait.image
        ?: (if (portrait.rejectedSize) client.attempt(prompt).image else null)
        ?: return@withContext null
      // Closed while Meta was drawing: nothing is written, the sheet already forgot this drawing.
      ensureActive()
      try {
        discardFiles(agentId)
        // A new name each time, so the image loader never shows the previous drawing from its cache.
        File(dir(), "${safe(agentId)}-${System.currentTimeMillis()}.img").apply { writeBytes(image.bytes) }
      } catch (e: IOException) {
        Log.w(TAG, "Failed to save the mockup: ${e.message}")
        null
      }
    }

  /** Deletes the agent's mockup: the sheet closed, the screen was built from it, or the agent is gone. */
  suspend fun discard(agentId: String) = withContext(Dispatchers.IO) { discardFiles(agentId) }

  private fun discardFiles(agentId: String) {
    dir().listFiles { f -> f.name.startsWith("${safe(agentId)}-") }?.forEach { it.delete() }
  }

  private fun dir(): File = File(application.filesDir, DIR).apply { mkdirs() }

  private fun safe(agentId: String): String = agentId.replace(Regex("[^A-Za-z0-9_-]"), "_")

  private fun prompt(agentName: String, brief: ScreenBrief, dark: Boolean): String = buildString {
    append("A clean, flat mobile app screen UI mockup, seen straight on, filling the whole frame, ")
    append("no phone frame, no hands, no photos of people. ")
    append("The app screen for \"").append(agentName).append("\": ").append(brief.text.trim().take(PROMPT_BRIEF_MAX)).append(". ")
    if (brief.blocks.isNotEmpty()) {
      append("It shows: ")
      append(ScreenBlock.entries.filter { it in brief.blocks }.joinToString("; ") { it.forForge })
      append(". ")
    }
    append("Layout: ").append(brief.layout.forForge).append(". ")
    if (dark) {
      append("Dark theme: near-black background, dark grey rounded cards, light grey text, lime green accent buttons. ")
    } else {
      append("Light theme: warm off-white paper background, light grey rounded cards, dark text, olive green accent buttons. ")
    }
    append("Modern sans-serif type, generous spacing, short readable English labels.")
  }

  private companion object {
    const val TAG = "ScreenMockup"
    const val DIR = "forge_mockups"

    /** The picture needs the gist, not every word: the forge still gets the whole brief. */
    const val PROMPT_BRIEF_MAX = 600
  }
}
