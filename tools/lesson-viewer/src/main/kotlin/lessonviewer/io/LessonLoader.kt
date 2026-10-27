package lessonviewer.io

import kotlinx.serialization.json.Json
import lessonviewer.model.Lesson
import java.io.File

/** One lesson file: either parsed or not. A broken file is NOT hidden — we show the reason. */
sealed interface LoadedLesson {
  val file: File

  data class Ok(override val file: File, val lesson: Lesson) : LoadedLesson
  data class Failed(override val file: File, val message: String) : LoadedLesson
}

object LessonLoader {
  private val json = Json { ignoreUnknownKeys = true }

  /** All `*.json` files of the directory, sorted by file name. The ROADMAP_LEVELS order is not copied here. */
  fun loadAll(dir: File): List<LoadedLesson> =
    (dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) } ?: emptyArray())
      .sortedBy { it.name }
      .map { load(it) }

  fun load(file: File): LoadedLesson = try {
    // readText() is UTF-8 by default. Do NOT replace it with defaultCharset(): the JVM on the developer's
    // machine starts with -Dfile.encoding=windows-1251, and the lessons would turn into gibberish.
    LoadedLesson.Ok(file, json.decodeFromString(Lesson.serializer(), file.readText()))
  } catch (e: Exception) {
    LoadedLesson.Failed(file, e.message ?: e::class.simpleName ?: "ошибка разбора")
  }
}
