package com.aiopenpocket.agent.core.lesson

// @trace kartinki-urokov-cherez-muse-image-fd6f8c

import android.app.Application
import android.util.Log
import com.aiopenpocket.agent.core.llm.ImageGenClient
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The image for a lesson step by its [LessonStep.imagePrompt]: from the disk cache, or from Muse Image
 * if it is not there.
 *
 * The cache is files in `filesDir/lesson_images`, named by a hash of the model and the full prompt.
 * Going through the lesson again, reopening the chat and scrolling back do not redraw the image: every
 * generation costs money and tens of seconds. `filesDir`, not `cacheDir`: the system clears `cacheDir`
 * whenever it likes, and a lesson in progress would lose images already paid for.
 *
 * One prompt is drawn no more than once at a time: the lesson prefetch and showing the step may ask for
 * the same image almost at once, and without a per-key lock that would be two paid generations of the
 * same frame.
 */
@SingleIn(AppScope::class)
@Inject
class LessonImageGenerator(
  private val application: Application,
  private val client: ImageGenClient,
) {

  private val locks = ConcurrentHashMap<String, Mutex>()

  private val dir: File get() = File(application.filesDir, DIR_NAME)

  /** The image file, or `null` if drawing failed (no key, no network, a provider refusal). */
  suspend fun imageFor(prompt: String): File? = withContext(Dispatchers.IO) {
    // The key is read from EncryptedSharedPreferences, and the first access to them brings up the Keystore
    // — so the whole path runs on IO, not on the caller's thread (which may be viewModelScope).
    val fullPrompt = styled(prompt)
    val name = cacheName(fullPrompt)
    file(name).takeIf { it.isUsable() }?.let { return@withContext it }
    locks.getOrPut(name) { Mutex() }.withLock {
      // While we waited for the lock, another call may have finished the image.
      file(name).takeIf { it.isUsable() }?.let { return@withLock it }
      val image = client.generate(fullPrompt) ?: return@withLock null
      write(name, image.bytes)
    }
  }

  private fun styled(prompt: String): String = "${prompt.trim()}\n\n$LESSON_IMAGE_STYLE"

  private fun cacheName(fullPrompt: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest("${client.model}\n$fullPrompt".toByteArray())
    return digest.joinToString("") { "%02x".format(it) }.take(32)
  }

  private fun file(name: String) = File(dir, "$name$EXTENSION")

  private fun File.isUsable() = isFile && length() > 0

  /**
   * Write via a temporary file and a rename: a reader (Coil showing the card) sees either the old state
   * or the whole image, never a stub. A write failure gives `null`: the image is already paid for, but
   * the lesson goes on without it anyway.
   */
  private fun write(name: String, bytes: ByteArray): File? = try {
    dir.mkdirs()
    val target = file(name)
    val tmp = File(dir, "$name.tmp")
    tmp.writeBytes(bytes)
    if (tmp.renameTo(target)) target else null.also { tmp.delete() }
  } catch (e: IOException) {
    Log.w(TAG, "Failed to cache lesson image: ${e.message}")
    null
  }

  private companion object {
    const val TAG = "LessonImageGenerator"
    const val DIR_NAME = "lesson_images"
    // The extension is nominal: Coil detects the image type (png/webp/jpeg) from the file content.
    const val EXTENSION = ".img"
  }
}

/**
 * The shared style of all lesson images. Without it every step would look like it came from a different
 * book. "No text" — because the card has its own caption, and models draw letters, Cyrillic especially,
 * badly. A horizontal frame — for the 16:9 card: `meta/muse-image` has no shape parameter.
 *
 * The pre-generation script `tools/lesson-images/pregenerate.py` appends the same string verbatim —
 * otherwise images from the build and those drawn on the phone would be in different styles.
 * LessonImageStyleSyncTest catches a mismatch.
 */
internal const val LESSON_IMAGE_STYLE = "Flat, clean editorial illustration for a mobile lesson about AI. " +
  "Light background, soft colors, wide 16:9 landscape composition. " +
  "No text, letters or numbers anywhere in the image."
