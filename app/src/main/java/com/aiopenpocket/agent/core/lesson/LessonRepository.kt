package com.aiopenpocket.agent.core.lesson

// @trace uroki-kontseptsii-v-chate-po-json-kontraktu-0a70c2

import android.content.Context
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads lessons from bundled assets. For the MVP the only source is `assets/lessons/{id}.json`;
 * a downloaded/server source can be layered in front later without touching callers.
 */
@Inject
@SingleIn(AppScope::class)
class LessonRepository(
  private val context: Context,
) {
  private val json = Json { ignoreUnknownKeys = true }

  suspend fun getLesson(id: String): Lesson = withContext(Dispatchers.IO) {
    val raw = context.assets.open("lessons/$id.json").bufferedReader().use { it.readText() }
    json.decodeFromString(Lesson.serializer(), raw)
  }

  /** Как [getLesson], но возвращает null, если урока с таким id нет (нет ассета или не парсится). */
  suspend fun getLessonOrNull(id: String): Lesson? = withContext(Dispatchers.IO) {
    runCatching { getLesson(id) }.getOrNull()
  }
}
