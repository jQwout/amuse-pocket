package lessonviewer.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Калька формата `app/src/main/assets/lessons/<id>.json`. Модель СОЗНАТЕЛЬНО продублирована, а не
 * подтянута из :app — см. решение 2.1 спеки. Расхождение ловится [lessonviewer.AllLessonsParseTest].
 *
 * Осторожно с KDoc: последовательность «слэш-звёздочка» внутри блочного комментария открывает
 * ВЛОЖЕННЫЙ комментарий (Kotlin их поддерживает), и внешний остаётся незакрытым. Поэтому пути с
 * масками пишем без звёздочки после слэша.
 */
@Serializable
data class Lesson(
  val id: String = "",
  val title: String = "",
  val description: String = "",
  val steps: List<LessonStep> = emptyList(),
)

/** Плоский шаг: дискриминатор [type], все остальные поля опциональны — как в :app. */
@Serializable
data class LessonStep(
  val type: String = "",
  // text и служебные
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
  /** Полиморфно: у "sort" — массив индексов, у "scenario" — одно число. Разбор — в LessonAnswers. */
  val correct: JsonElement? = null,
  val explain: String = "",
  // input / challenge — в уроках пока не встречаются, поля держим, чтобы не падать
  val prompt: String = "",
  val hint: String = "",
  val task: String = "",
)

@Serializable
data class QuizQuestion(
  val q: String = "",
  val options: List<String> = emptyList(),
  /** -1, а не 0: при отсутствующем/битом ключе НЕ помечаем первый вариант правильным. */
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

/** Типы, которые вьюер умеет рисовать. Незнакомый тип рисуется красной плашкой, а не пропускается. */
val KNOWN_STEP_TYPES: Set<String> = setOf(
  "text", "quiz", "scenario", "reveal", "image", "concept",
  "match", "sort", "fill", "example",
  "profile", "persona", "language",
  "input", "challenge",
)
