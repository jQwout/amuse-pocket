package lessonviewer.io

import java.io.File

/**
 * Где лежат уроки. По умолчанию — относительно рабочего каталога процесса: при `gradlew -p
 * tools/lesson-viewer run` это сам `tools/lesson-viewer/`, поэтому два уровня вверх дают корень репо.
 */
object LessonsDir {
  const val PROPERTY = "lessons.dir"
  const val DEFAULT_RELATIVE = "../../app/src/main/assets/lessons"

  /** Каталог с уроками, либо null — вызывающий показывает подсказку, а не падает. */
  fun resolve(): File? {
    val override = System.getProperty(PROPERTY)
    val candidate = if (override.isNullOrBlank()) File(DEFAULT_RELATIVE) else File(override)
    return candidate.takeIf { it.isDirectory }?.absoluteFile
  }

  /** Путь, который искали, — для сообщения об ошибке. */
  fun expectedPath(): String =
    (System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT_RELATIVE)
      .let { File(it).absolutePath }
}
