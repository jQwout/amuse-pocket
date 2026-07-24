package lessonviewer.io

import kotlinx.serialization.json.Json
import lessonviewer.model.Lesson
import java.io.File

/** Один файл урока: либо разобран, либо нет. Битый файл НЕ прячем — показываем причину. */
sealed interface LoadedLesson {
  val file: File

  data class Ok(override val file: File, val lesson: Lesson) : LoadedLesson
  data class Failed(override val file: File, val message: String) : LoadedLesson
}

object LessonLoader {
  private val json = Json { ignoreUnknownKeys = true }

  /** Все `*.json` каталога, отсортированные по имени файла. Порядок ROADMAP_LEVELS не копируем. */
  fun loadAll(dir: File): List<LoadedLesson> =
    (dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) } ?: emptyArray())
      .sortedBy { it.name }
      .map { load(it) }

  fun load(file: File): LoadedLesson = try {
    // readText() — UTF-8 по умолчанию. НЕ заменять на defaultCharset(): JVM на машине разработчика
    // стартует с -Dfile.encoding=windows-1251, и уроки поедут кракозябрами.
    LoadedLesson.Ok(file, json.decodeFromString(Lesson.serializer(), file.readText()))
  } catch (e: Exception) {
    LoadedLesson.Failed(file, e.message ?: e::class.simpleName ?: "ошибка разбора")
  }
}
