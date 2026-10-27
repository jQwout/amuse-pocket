package lessonviewer.io

import java.io.File

/**
 * Where the lessons are. By default relative to the process's working directory: with `gradlew -p
 * tools/lesson-viewer run` that is `tools/lesson-viewer/` itself, so two levels up give the repo root.
 */
object LessonsDir {
  const val PROPERTY = "lessons.dir"
  const val DEFAULT_RELATIVE = "../../app/src/main/assets/lessons"

  /** The directory with the lessons, or null — the caller shows a hint instead of crashing. */
  fun resolve(): File? {
    val override = System.getProperty(PROPERTY)
    val candidate = if (override.isNullOrBlank()) File(DEFAULT_RELATIVE) else File(override)
    return candidate.takeIf { it.isDirectory }?.absoluteFile
  }

  /** The path that was searched — for the error message. */
  fun expectedPath(): String =
    (System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() } ?: DEFAULT_RELATIVE)
      .let { File(it).absolutePath }
}
