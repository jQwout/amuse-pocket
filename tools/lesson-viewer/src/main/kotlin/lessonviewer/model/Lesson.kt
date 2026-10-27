package lessonviewer.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A copy of the `app/src/main/assets/lessons/<id>.json` format. The model is DELIBERATELY duplicated, not
 * pulled in from :app — see decision 2.1 of the spec. A mismatch is caught by [lessonviewer.AllLessonsParseTest].
 *
 * Careful with KDoc: the "slash-asterisk" sequence inside a block comment opens a NESTED comment (Kotlin
 * supports them), and the outer one stays unclosed. So paths with masks are written without an asterisk
 * after a slash.
 */
@Serializable
data class Lesson(
  val id: String = "",
  val title: String = "",
  val description: String = "",
  val steps: List<LessonStep> = emptyList(),
)

/** A flat step: the [type] discriminator, all other fields optional — as in :app. */
@Serializable
data class LessonStep(
  val type: String = "",
  // text and service steps
  val text: String = "",
  val note: String = "",
  // quiz
  val questions: List<QuizQuestion> = emptyList(),
  // image
  val asset: String = "",
  val url: String = "",
  val caption: String = "",
  val imageKey: String = "",
  val alt: String = "",
  // reveal / fill
  val question: String = "",
  val answer: String = "",
  val template: String = "",
  val caseSensitive: Boolean = false,
  // concept
  val term: String = "",
  val definition: String = "",
  val example: String = "",
  // example
  val label: String = "",
  val parts: List<ExamplePart> = emptyList(),
  // sort / match
  val instruction: String = "",
  val items: List<String> = emptyList(),
  val pairs: List<MatchPair> = emptyList(),
  // scenario
  val situation: String = "",
  val q: String = "",
  val options: List<String> = emptyList(),
  /** Polymorphic: "sort" has an array of indices, "scenario" a single number. Parsing is in LessonAnswers. */
  val correct: JsonElement? = null,
  val explain: String = "",
  // input / challenge — not used in lessons yet; the fields are kept so as not to fail
  val prompt: String = "",
  val hint: String = "",
  val task: String = "",
)

@Serializable
data class QuizQuestion(
  val q: String = "",
  val options: List<String> = emptyList(),
  /** -1, not 0: with a missing/broken key the first option is NOT marked as correct. */
  val correct: Int = -1,
  val explain: String = "",
)

@Serializable
data class ExamplePart(
  val text: String = "",
  val role: String = "",
  val note: String = "",
)

@Serializable
data class MatchPair(
  val term: String = "",
  val def: String = "",
)

/** The types the viewer can draw. An unknown type is drawn as a red plate, not skipped. */
val KNOWN_STEP_TYPES: Set<String> = setOf(
  "text", "quiz", "scenario", "reveal", "image", "concept",
  "match", "sort", "fill", "example",
  "profile", "persona", "language",
  "input", "challenge",
)
