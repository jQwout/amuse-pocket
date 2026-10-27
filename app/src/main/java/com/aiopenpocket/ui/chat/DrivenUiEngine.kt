package com.aiopenpocket.ui.chat

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153
// @trace sam-segmentation-boxes-in-a-lesson-c3ea52

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.aiopenpocket.agent.core.llm.SegmentBox
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.roadmap.QuizQuestion
import org.json.JSONArray
import org.json.JSONObject


// Defined structures of Templates
sealed class DrivenUiCard {
    data class TodoList(val title: String, val items: List<TodoItem>) : DrivenUiCard()
    data class WeatherCard(val city: String, val days: List<WeatherDay>) : DrivenUiCard()
    data class TimerCard(val label: String, val durationSec: Int) : DrivenUiCard()
    data class TableCard(val headers: List<String>, val rows: List<List<String>>) : DrivenUiCard()
    data class ChartBarCard(val title: String, val labels: List<String>, val values: List<Float>) : DrivenUiCard()
    data class FormCard(val fields: List<FormField>, val submitLabel: String) : DrivenUiCard()
    data class ChoiceCard(val question: String, val options: List<String>, val answerIndex: Int) : DrivenUiCard()
    data class LessonQuizCard(val questions: List<QuizQuestion>) : DrivenUiCard()
    /** [primaryAction] is non-null ONLY for the AI Academy capstone's real completion card (Task 23) — see [DrivenUiEngine.parseJsonString]'s `lesson_complete` branch and `LessonChatController.postCompletion`. */
    data class LessonCompleteCard(val title: String, val subtitle: String, val buttonLabel: String, val primaryAction: LessonPrimaryAction? = null) : DrivenUiCard()
    data class SwitchCard(val label: String, val description: String, val action: String, val initialOn: Boolean) : DrivenUiCard()
    data class NotificationPermissionCard(val title: String, val description: String, val buttonLabel: String) : DrivenUiCard()
    /** A live heartbeat card: its content is pulled from Room by [heartbeatId] (see [ReminderCardView]). */
    data class ReminderCard(val heartbeatId: String) : DrivenUiCard()
    /** [prompt] is the lesson step's imagePrompt: the "Draw it for me" button redraws the image from it. */
    data class ImageCard(val asset: String, val url: String, val caption: String, val prompt: String = "") : DrivenUiCard()
    /**
     * The "video" lesson step: [instruction] is what to film, [prompt] is the task for the model about the
     * clip, [sampleVideo] and [samplePreview] are the sample clip from assets for the "Show me an example"
     * button (empty — only your own video).
     */
    data class LessonVideoCard(
        val instruction: String,
        val prompt: String,
        val sampleVideo: String = "",
        val samplePreview: String = "",
    ) : DrivenUiCard()
    /**
     * The "segment" lesson step: [instruction] is what to do, [prompt] is the DEFAULT thing for SAM to find (an
     * English noun phrase, editable on the card), [sampleImage] is the bundled photo for the one-tap button
     * (empty — only your own photo).
     */
    data class LessonSegmentCard(
        val instruction: String,
        val prompt: String,
        val sampleImage: String = "",
    ) : DrivenUiCard()
    /**
     * SAM's answer to a "segment" step: the photo at [imageUrl] ([imageWidth] x [imageHeight] is the grid that was
     * sent) and a frame per match in [boxes]. [total] can exceed `boxes.size`: the card keeps the first few.
     * An empty [boxes] is a normal answer — "nothing found" — not an error.
     */
    data class LessonSegmentResultCard(
        val imageUrl: String,
        val imageWidth: Int,
        val imageHeight: Int,
        val concept: String,
        val total: Int,
        val boxes: List<SegmentResultBox>,
    ) : DrivenUiCard()
    /** The "animation" lesson step: the procedural scene [scene] (see LESSON_ANIMATION_SCENES) with a caption. */
    data class LessonAnimationCard(val scene: String, val caption: String) : DrivenUiCard()
    data class RevealCard(val question: String, val answer: String) : DrivenUiCard()
    data class ConceptCard(val term: String, val definition: String, val example: String) : DrivenUiCard()
    data class ExampleCard(val label: String, val parts: List<ExamplePart>) : DrivenUiCard()
    /**
     * The "fill" step: [answer] is the canonical answer (shown after solving), [answers] are other accepted
     * variants. The check lives here, not in the composable: it is pure logic, and plain JUnit runs it.
     */
    data class FillCard(
        val template: String,
        val answer: String,
        val caseSensitive: Boolean,
        val answers: List<String> = emptyList(),
    ) : DrivenUiCard() {
        /**
         * Whether the input counts. Leading/trailing and repeated spaces do not matter; without
         * [caseSensitive] neither case nor "ё"/"е" matters — almost nobody types "ё" on a phone keyboard,
         * and an answer right in meaning must not hit a wall. Empty input never counts, even if an empty
         * string accidentally got into the answers.
         */
        fun accepts(input: String): Boolean {
            val given = normalizeFill(input)
            if (given.isEmpty()) return false
            return (listOf(answer) + answers).any { normalizeFill(it) == given }
        }

        private fun normalizeFill(raw: String): String {
            val collapsed = raw.trim().replace(Regex("\\s+"), " ")
            return if (caseSensitive) collapsed else collapsed.lowercase(java.util.Locale.ROOT).replace('ё', 'е')
        }
    }
    data class SortCard(val instruction: String, val items: List<String>, val correct: List<Int>, val explain: String) : DrivenUiCard()
    data class MatchCard(val instruction: String, val pairs: List<MatchPair>) : DrivenUiCard()
    data class ScenarioCard(val situation: String, val question: String, val options: List<String>, val answerIndex: Int, val explain: String) : DrivenUiCard()
    data class ErrorCard(val message: String) : DrivenUiCard()
}

data class TodoItem(val text: String, var done: Boolean)
data class WeatherDay(val dayName: String, val temp: String, val text: String, val icon: String)
data class FormField(val name: String, val label: String, val placeholder: String, val type: String, var value: String = "")
data class ExamplePart(val text: String, val role: String, val note: String)
data class MatchPair(val term: String, val def: String)
/** One frame of a `lesson_segment_result` card: SAM's object id and its box (with the picture size it was drawn on). */
data class SegmentResultBox(val id: String, val box: SegmentBox)
/** `lesson_complete` card's optional CTA (Task 23) — see [DrivenUiCard.LessonCompleteCard]. */
data class LessonPrimaryAction(val type: String, val label: String)

/** `type` values [DrivenUiEngine.parseJsonString] accepts for a `lesson_complete` card's `primary_action` — any other value is rejected as an [DrivenUiCard.ErrorCard], never silently dropped. */
private val KNOWN_LESSON_PRIMARY_ACTION_TYPES = setOf("open_agent_builder")

private fun JSONArray.toStringList(): List<String> =
    List(length()) { optString(it, "") }

private fun JSONArray.toIntList(): List<Int> =
    List(length()) { optInt(it, 0) }

private inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(optJSONObject(it) ?: JSONObject()) }

private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

object DrivenUiEngine {

    fun parseJsonString(specString: String): DrivenUiCard = try {
        val root = JSONObject(specString)
        val template = root.optString("template", "")
        val p = root.optJSONObject("params") ?: JSONObject()

        when (template) {
            "todo_list" -> DrivenUiCard.TodoList(
                title = p.optString("title", tr("Список дел", "To-do list")),
                items = p.array("items").mapObjects {
                    TodoItem(
                        text = it.optString("item_text", ""),
                        done = it.optBoolean("item_done", false),
                    )
                },
            )
            "weather_card" -> DrivenUiCard.WeatherCard(
                city = p.optString("city", tr("Погода", "Weather")),
                days = p.array("days").mapObjects {
                    WeatherDay(
                        dayName = it.optString("day", ""),
                        temp = it.optString("temp", ""),
                        text = it.optString("text", tr("Ясно", "Clear")),
                        icon = it.optString("icon", "clear"),
                    )
                },
            )
            "timer" -> DrivenUiCard.TimerCard(
                label = p.optString("label", tr("Таймер", "Timer")),
                durationSec = p.optInt("duration_sec", 60),
            )
            "table" -> {
                val rowsJson = p.array("rows")
                DrivenUiCard.TableCard(
                    headers = p.array("headers").toStringList(),
                    rows = List(rowsJson.length()) {
                        (rowsJson.optJSONArray(it) ?: JSONArray()).toStringList()
                    },
                )
            }
            "chart_bar" -> {
                val valuesJson = p.array("values")
                DrivenUiCard.ChartBarCard(
                    title = p.optString("title", tr("График", "Chart")),
                    labels = p.array("labels").toStringList(),
                    values = List(valuesJson.length()) {
                        valuesJson.optDouble(it, 0.0).toFloat()
                    },
                )
            }
            "form" -> DrivenUiCard.FormCard(
                fields = p.array("fields").mapObjects {
                    FormField(
                        name = it.optString("name", "field"),
                        label = it.optString("label", tr("Поле", "Field")),
                        placeholder = it.optString("placeholder", ""),
                        type = it.optString("type", "text"),
                    )
                },
                submitLabel = p.optString("submit_label", tr("Отправить", "Submit")),
            )
            "choice" -> DrivenUiCard.ChoiceCard(
                question = p.optString("question", ""),
                options = p.array("options").toStringList(),
                answerIndex = if (p.has("answer")) p.optInt("answer", -1) else -1,
            )
            "lesson_quiz" -> DrivenUiCard.LessonQuizCard(
                questions = p.array("questions").mapObjects {
                    QuizQuestion(
                        question = it.optString("q", ""),
                        options = it.array("options").toStringList(),
                        correctIndex = it.optInt("correct", -1),
                        explanation = it.optString("explain", ""),
                    )
                },
            )
            "lesson_complete" -> {
                // primary_action is an optional CTA (Task 23): today ONLY LessonChatController.postCompletion puts it
                // in, for the agent_blueprint capstone; ordinary lessons do not pass this key at all, and the
                // card stays as before (primaryAction = null).
                val actionJson = p.optJSONObject("primary_action")
                val actionType = actionJson?.optString("type", "").orEmpty()
                if (actionJson != null && actionType !in KNOWN_LESSON_PRIMARY_ACTION_TYPES) {
                    // An unknown type is rejected entirely (like "reminder" without heartbeat_id below) rather than
                    // silently ignored — a broken/outdated CTA must not quietly vanish from sight.
                    DrivenUiCard.ErrorCard(
                        tr(
                            "lesson_complete: неизвестный тип primary_action: '$actionType'",
                            "lesson_complete: unknown primary_action type: '$actionType'",
                        ),
                    )
                } else {
                    DrivenUiCard.LessonCompleteCard(
                        title = p.optString("title", tr("Урок пройден!", "Lesson complete!")),
                        subtitle = p.optString("subtitle", ""),
                        buttonLabel = p.optString("button_label", tr("Продолжить", "Continue")),
                        primaryAction = actionJson?.let {
                            LessonPrimaryAction(
                                type = actionType,
                                label = it.optString("label", tr("Открыть конструктор", "Open the builder")),
                            )
                        },
                    )
                }
            }
            "switch" -> DrivenUiCard.SwitchCard(
                label = p.optString("label", tr("Переключатель", "Switch")),
                description = p.optString("description", ""),
                action = p.optString("action", ""),
                initialOn = p.optBoolean("initial", false),
            )
            "notification_permission" -> DrivenUiCard.NotificationPermissionCard(
                title = p.optString("title", tr("Уведомления", "Notifications")),
                description = p.optString(
                    "description",
                    tr("Разреши уведомления, чтобы получать напоминания.", "Allow notifications to get reminders."),
                ),
                buttonLabel = p.optString("button_label", tr("Разрешить уведомления", "Allow notifications")),
            )
            "reminder" -> {
                val id = p.optString("heartbeat_id", "")
                if (id.isBlank()) DrivenUiCard.ErrorCard(tr("reminder: нет heartbeat_id", "reminder: missing heartbeat_id"))
                else DrivenUiCard.ReminderCard(id)
            }
            "image" -> DrivenUiCard.ImageCard(
                asset = p.optString("asset", ""),
                url = p.optString("url", ""),
                caption = p.optString("caption", ""),
                prompt = p.optString("image_prompt", ""),
            )
            "lesson_animation" -> DrivenUiCard.LessonAnimationCard(
                scene = p.optString("scene", ""),
                caption = p.optString("caption", ""),
            )
            "lesson_video" -> DrivenUiCard.LessonVideoCard(
                instruction = p.optString("instruction", ""),
                prompt = p.optString("video_prompt", ""),
                sampleVideo = p.optString("sample_video", ""),
                samplePreview = p.optString("sample_preview", ""),
            )
            "lesson_segment" -> DrivenUiCard.LessonSegmentCard(
                instruction = p.optString("instruction", ""),
                prompt = p.optString("segment_prompt", ""),
                sampleImage = p.optString("sample_image", ""),
            )
            "lesson_segment_result" -> {
                // A box with no picture size cannot be scaled (and would divide by zero), so it is dropped.
                val boxes = p.array("boxes").mapObjects {
                    SegmentResultBox(
                        id = it.optString("id", ""),
                        box = SegmentBox(
                            x1 = it.optInt("x1", 0),
                            y1 = it.optInt("y1", 0),
                            x2 = it.optInt("x2", 0),
                            y2 = it.optInt("y2", 0),
                            width = it.optInt("w", 0),
                            height = it.optInt("h", 0),
                            confidence = if (it.has("c")) it.optDouble("c") else null,
                        ),
                    )
                }.filter { it.box.width > 0 && it.box.height > 0 }
                DrivenUiCard.LessonSegmentResultCard(
                    imageUrl = p.optString("image_url", ""),
                    imageWidth = p.optInt("image_w", 0),
                    imageHeight = p.optInt("image_h", 0),
                    concept = p.optString("concept", ""),
                    total = p.optInt("total", boxes.size),
                    boxes = boxes,
                )
            }
            "reveal" -> DrivenUiCard.RevealCard(
                question = p.optString("question", ""),
                answer = p.optString("answer", ""),
            )
            "concept" -> DrivenUiCard.ConceptCard(
                term = p.optString("term", ""),
                definition = p.optString("definition", ""),
                example = p.optString("example", ""),
            )
            "example" -> DrivenUiCard.ExampleCard(
                label = p.optString("label", ""),
                parts = p.array("parts").mapObjects {
                    ExamplePart(
                        text = it.optString("text", ""),
                        role = it.optString("role", ""),
                        note = it.optString("note", ""),
                    )
                },
            )
            "fill" -> DrivenUiCard.FillCard(
                template = p.optString("template", ""),
                answer = p.optString("answer", ""),
                caseSensitive = p.optBoolean("case_sensitive", false),
                // An optional list: old cards and cards from the model without it parse as before.
                answers = p.array("answers").toStringList().filter { it.isNotBlank() },
            )
            "sort" -> DrivenUiCard.SortCard(
                instruction = p.optString("instruction", ""),
                items = p.array("items").toStringList(),
                correct = p.array("correct").toIntList(),
                explain = p.optString("explain", ""),
            )
            "match" -> DrivenUiCard.MatchCard(
                instruction = p.optString("instruction", ""),
                pairs = p.array("pairs").mapObjects {
                    MatchPair(term = it.optString("term", ""), def = it.optString("def", ""))
                },
            )
            "scenario" -> DrivenUiCard.ScenarioCard(
                situation = p.optString("situation", ""),
                question = p.optString("q", ""),
                options = p.array("options").toStringList(),
                answerIndex = p.optInt("correct", -1),
                explain = p.optString("explain", ""),
            )
            else -> DrivenUiCard.ErrorCard(tr("Неизвестный шаблон: $template", "Unknown template: $template"))
        }
    } catch (e: Exception) {
        DrivenUiCard.ErrorCard(tr("Ошибка парсинга шаблона: ${e.localizedMessage}", "Template parse error: ${e.localizedMessage}"))
    }
}

/**
 * Renders a parsed card as Markdown. Used in markdown-only mode (card widgets disabled):
 * the chat shows render_card results as markdown tables/lists instead of interactive views.
 */
fun DrivenUiCard.toMarkdown(): String = when (this) {
    is DrivenUiCard.TodoList -> buildString {
        appendLine("**$title**")
        if (items.isEmpty()) {
            appendLine(tr("_Нет задач_", "_No tasks_"))
        } else {
            items.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.text}") }
        }
    }.trim()

    is DrivenUiCard.WeatherCard -> buildString {
        appendLine(tr("**Погода: $city**", "**Weather: $city**"))
        appendLine()
        appendLine(tr("| День | Темп | Описание |", "| Day | Temp | Conditions |"))
        appendLine("| --- | --- | --- |")
        days.forEach { appendLine("| ${it.dayName} | ${it.temp} | ${it.text} |") }
    }.trim()

    is DrivenUiCard.TimerCard -> tr("**$label** — таймер на $durationSec сек.", "**$label** — timer for $durationSec sec.")

    is DrivenUiCard.TableCard -> buildString {
        if (headers.isNotEmpty()) {
            appendLine("| ${headers.joinToString(" | ")} |")
            appendLine("| ${headers.joinToString(" | ") { "---" }} |")
        }
        rows.forEach { row -> appendLine("| ${row.joinToString(" | ")} |") }
    }.trim()

    is DrivenUiCard.ChartBarCard -> buildString {
        appendLine("**$title**")
        appendLine()
        val maxVal = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        labels.forEachIndexed { i, label ->
            val v = values.getOrNull(i) ?: 0f
            val bars = "█".repeat((v / maxVal * 10).toInt().coerceIn(0, 10))
            val valueText = if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
            appendLine("- $label: $bars $valueText")
        }
    }.trim()

    is DrivenUiCard.FormCard -> buildString {
        appendLine(tr("**Форма**", "**Form**"))
        fields.forEach {
            val ph = if (it.placeholder.isNotBlank()) " — _${it.placeholder}_" else ""
            appendLine("- ${it.label}$ph")
        }
        appendLine()
        appendLine("_[$submitLabel]_")
    }.trim()

    is DrivenUiCard.ChoiceCard -> buildString {
        if (question.isNotBlank()) appendLine("**$question**")
        options.forEach { appendLine("- $it") }
    }.trim()

    is DrivenUiCard.LessonQuizCard -> buildString {
        appendLine(tr("**Проверка**", "**Quiz**"))
        questions.forEachIndexed { i, q ->
            appendLine("${i + 1}. ${q.question}")
            q.options.forEach { appendLine("   - $it") }
        }
    }.trim()

    is DrivenUiCard.LessonCompleteCard -> buildString {
        appendLine("**✓ $title**")
        if (subtitle.isNotBlank()) appendLine(subtitle)
        appendLine("_[$buttonLabel]_")
    }.trim()

    is DrivenUiCard.SwitchCard -> buildString {
        appendLine("**$label** _[${if (initialOn) tr("вкл", "on") else tr("выкл", "off")}]_")
        if (description.isNotBlank()) appendLine(description)
    }.trim()

    is DrivenUiCard.NotificationPermissionCard -> buildString {
        appendLine("**$title**")
        if (description.isNotBlank()) appendLine(description)
        appendLine("_[$buttonLabel]_")
    }.trim()

    // A live card: in markdown mode (widgets disabled) the DB status cannot be pulled in — only a placeholder.
    is DrivenUiCard.ReminderCard -> tr("⏰ _Напоминание (id=$heartbeatId)_", "⏰ _Reminder (id=$heartbeatId)_")

    is DrivenUiCard.ImageCard -> buildString {
        val src = asset.ifBlank { url }
        appendLine("🖼️ ${caption.ifBlank { tr("Изображение", "Image") }}")
        if (src.isNotBlank()) appendLine("_($src)_")
    }.trim()

    is DrivenUiCard.LessonVideoCard -> "🎥 ${instruction.ifBlank { tr("Сними короткое видео", "Film a short video") }}"

    is DrivenUiCard.LessonSegmentCard -> "🔍 ${instruction.ifBlank { tr("Найди предмет на фото", "Find an object in a photo") }}"

    is DrivenUiCard.LessonSegmentResultCard ->
        "🔍 " + tr("«$concept»: найдено $total", "“$concept”: $total found")

    is DrivenUiCard.LessonAnimationCard -> "🎬 ${caption.ifBlank { tr("Анимация урока", "Lesson animation") }}"

    is DrivenUiCard.RevealCard -> buildString {
        appendLine("**$question**")
        appendLine("> $answer")
    }.trim()

    is DrivenUiCard.ConceptCard -> buildString {
        appendLine("**$term** — $definition")
        if (example.isNotBlank()) appendLine(tr("_Пример: ${example}_", "_Example: ${example}_"))
    }.trim()

    is DrivenUiCard.ExampleCard -> buildString {
        if (label.isNotBlank()) appendLine("**$label**")
        parts.forEach { p ->
            val note = if (p.note.isNotBlank()) " — _${p.note}_" else ""
            appendLine("- ${if (p.role.isNotBlank()) "[${p.role}] " else ""}${p.text}$note")
        }
    }.trim()

    is DrivenUiCard.FillCard -> tr("**Заполни пропуск:** ", "**Fill in the blank:** ") + template.replace("___", "_____")

    is DrivenUiCard.SortCard -> buildString {
        if (instruction.isNotBlank()) appendLine("**$instruction**")
        items.forEachIndexed { i, it -> appendLine("${i + 1}. $it") }
    }.trim()

    is DrivenUiCard.MatchCard -> buildString {
        if (instruction.isNotBlank()) appendLine("**$instruction**")
        pairs.forEach { appendLine("- ${it.term} → ${it.def}") }
    }.trim()

    is DrivenUiCard.ScenarioCard -> buildString {
        if (situation.isNotBlank()) appendLine("_${situation}_")
        if (question.isNotBlank()) appendLine("**$question**")
        options.forEach { appendLine("- $it") }
    }.trim()

    is DrivenUiCard.ErrorCard -> "> ⚠️ $message"
}
