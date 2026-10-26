package com.aiopenpocket.agent.core.llm

// @trace streaming-llm-chat-6595a1
// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import android.app.Application
import android.util.Log
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import com.aiopenpocket.di.AppScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File

/** The catalog downloaded from OpenRouter, together with the download time (for the TTL). */
@Serializable
data class CachedModelCatalog(
  val fetchedAt: Long,
  val catalog: ModelCatalog,
)

/**
 * Cache of the OpenRouter model catalog — ONE JSON file in `filesDir`, not a Room table.
 *
 * It is a document that is replaced as a whole and never queried in parts: it has nothing relational
 * about it. Plus the cost of a mistake: until release the Room schema lives on
 * `fallbackToDestructiveMigration`, and adding a table FOR A CACHE would cost the user all their
 * conversations and agents on update. A file has no such cost.
 *
 * `filesDir`, not `cacheDir`: nobody asked the system to clear this mid-session, and it is tens of
 * kilobytes. Losing the file is not a problem anyway — [ModelCatalogLoader] falls back to the asset
 * seed.
 */
@SingleIn(AppScope::class)
@Inject
class OpenRouterCatalogCache(
  private val application: Application,
) {

  @OptIn(ExperimentalSerializationApi::class)
  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    namingStrategy = JsonNamingStrategy.SnakeCase
  }

  private val file: File get() = File(application.filesDir, FILE_NAME)

  /**
   * The cached catalog, or null: no file, or it is broken or empty.
   *
   * A broken file is NOT deleted. It used to be — and that turned any race between reading and writing
   * into data loss: [read] is called on every agent turn (through `ModelCatalogLoader.loadOpenRouter`
   * in the provider chain), and [write] from the background update, so they can overlap at any moment.
   * Now the write is atomic, and a read simply returns null: the next update overwrites the file.
   */
  @Synchronized
  fun read(): CachedModelCatalog? {
    val f = file
    if (!f.exists()) return null
    return try {
      json.decodeFromString<CachedModelCatalog>(f.readText())
        .takeIf { it.catalog.models.isNotEmpty() }
    } catch (e: Exception) {
      Log.w(TAG, "Cached OpenRouter catalog is unreadable, falling back to seed: ${e.message}")
      null
    }
  }

  /**
   * Replace via a temporary file: write next to it and rename.
   *
   * A plain `writeText` first truncates the file to zero — a reader hitting that window would see a
   * stub instead of the catalog. `renameTo` within one directory on Android is `rename(2)`, so a reader
   * sees either the whole old file or the whole new one.
   *
   * `java.nio.file.Files.move` must NOT be used here, convenient as it is: `java.nio.file` appeared on
   * Android only in API 26, the project's minSdk is 24 and there is no desugaring. On 7.x that is a
   * `NoClassDefFoundError` — an `Error`, not an `Exception`, so it would bypass the catch below and the
   * catch in the service: the app would crash on opening Settings. Throwable is caught for the same
   * reason: a model cache is no reason to bring the process down.
   */
  @Synchronized
  fun write(catalog: ModelCatalog, fetchedAt: Long) {
    val tmp = File(application.filesDir, "$FILE_NAME.tmp")
    try {
      tmp.writeText(json.encodeToString(CachedModelCatalog(fetchedAt, catalog)))
      // renameTo does NOT overwrite an existing file on Windows (it silently returns false). On Android
      // the first attempt always succeeds; the second branch is only for the host JVM, where Robolectric
      // runs and atomicity does not matter.
      if (!tmp.renameTo(file)) {
        file.delete()
        if (!tmp.renameTo(file)) {
          Log.w(TAG, "Failed to swap in the new OpenRouter catalog")
          tmp.delete()
        }
      }
    } catch (e: Throwable) {
      // Could not write — no reason to fail the turn: next time we simply go to the network again.
      Log.w(TAG, "Failed to cache OpenRouter catalog: ${e.message}")
      runCatching { tmp.delete() }
    }
  }

  private companion object {
    const val TAG = "OpenRouterCatalogCache"
    const val FILE_NAME = "openrouter_models.json"
  }
}
