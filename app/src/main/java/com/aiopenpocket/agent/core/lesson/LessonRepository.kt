package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import android.content.Context
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.AppLanguage
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads lessons from bundled assets: `assets/lessons/{id}.json` (Russian, every lesson) and, when the
 * interface is English, `assets/lessons/en/{id}.json` first. A lesson without an English copy falls
 * back to Russian — the course is translated lesson by lesson, starting with the demo path.
 * A downloaded/server source can be layered in front later without touching callers.
 */
@Inject
@SingleIn(AppScope::class)
class LessonRepository(
  private val context: Context,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun getLesson(id: String): Lesson = withContext(Dispatchers.IO) {
    val raw = readTranslated(id) ?: context.assets.open("lessons/$id.json").bufferedReader().use { it.readText() }
    json.decodeFromString(Lesson.serializer(), raw)
  }

  // @trace english-ui-on-the-demo-path-223241
  private fun readTranslated(id: String): String? {
    if (!AppLanguage.isEnglish) return null
    return runCatching {
      context.assets.open("lessons/${AppLanguage.EN}/$id.json").bufferedReader().use { it.readText() }
    }.getOrNull()
  }

  /** Like [getLesson], but returns null if there is no lesson with that id (no asset, or it does not parse). */
  suspend fun getLessonOrNull(id: String): Lesson? = withContext(Dispatchers.IO) {
    runCatching { getLesson(id) }.getOrNull()
  }
}
