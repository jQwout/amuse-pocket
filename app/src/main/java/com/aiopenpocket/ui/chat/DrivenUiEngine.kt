package com.aiopenpocket.ui.chat

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
    /** Живая карточка heartbeat'а: содержимое подтягивается по [heartbeatId] из Room (см. [ReminderCardView]). */
    data class ReminderCard(val heartbeatId: String) : DrivenUiCard()
    /** [prompt] — imagePrompt шага урока: по нему кнопка «Нарисуй под меня» перерисует картинку. */
    data class ImageCard(val asset: String, val url: String, val caption: String, val prompt: String = "") : DrivenUiCard()
    /**
     * Шаг урока "video": [instruction] — что снять, [prompt] — задание модели к ролику, [sampleVideo] и
     * [samplePreview] — ролик-пример из assets для кнопки «Показать на примере» (пусто — только своё видео).
     */
    data class LessonVideoCard(
        val instruction: String,
        val prompt: String,
        val sampleVideo: String = "",
        val samplePreview: String = "",
    ) : DrivenUiCard()
    /** Шаг урока "animation": процедурная сцена [scene] (см. LESSON_ANIMATION_SCENES) с подписью. */
    data class LessonAnimationCard(val scene: String, val caption: String) : DrivenUiCard()
    data class RevealCard(val question: String, val answer: String) : DrivenUiCard()
    data class ConceptCard(val term: String, val definition: String, val example: String) : DrivenUiCard()
    data class ExampleCard(val label: String, val parts: List<ExamplePart>) : DrivenUiCard()
    data class FillCard(val template: String, val answer: String, val caseSensitive: Boolean) : DrivenUiCard()
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
                title = p.optString("title", "Список дел"),
                items = p.array("items").mapObjects {
                    TodoItem(
                        text = it.optString("item_text", ""),
                        done = it.optBoolean("item_done", false),
                    )
                },
            )
            "weather_card" -> DrivenUiCard.WeatherCard(
                city = p.optString("city", "Погода"),
                days = p.array("days").mapObjects {
                    WeatherDay(
                        dayName = it.optString("day", ""),
                        temp = it.optString("temp", ""),
                        text = it.optString("text", "Ясно"),
                        icon = it.optString("icon", "clear"),
                    )
                },
            )
            "timer" -> DrivenUiCard.TimerCard(
                label = p.optString("label", "Таймер"),
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
                    title = p.optString("title", "График"),
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
                        label = it.optString("label", "Поле"),
                        placeholder = it.optString("placeholder", ""),
                        type = it.optString("type", "text"),
                    )
                },
                submitLabel = p.optString("submit_label", "Отправить"),
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
                // primary_action — опциональный CTA (Task 23): сегодня его кладёт ТОЛЬКО
                // LessonChatController.postCompletion для капстоуна agent_blueprint; обычные уроки
                // этот ключ не передают вовсе, и карточка остаётся прежней (primaryAction = null).
                val actionJson = p.optJSONObject("primary_action")
                val actionType = actionJson?.optString("type", "").orEmpty()
                if (actionJson != null && actionType !in KNOWN_LESSON_PRIMARY_ACTION_TYPES) {
                    // Неизвестный тип отвергаем целиком (как "reminder" без heartbeat_id ниже), а не
                    // молча игнорируем — битый/устаревший CTA не должен тихо пропадать из виду.
                    DrivenUiCard.ErrorCard("lesson_complete: неизвестный тип primary_action: '$actionType'")
                } else {
                    DrivenUiCard.LessonCompleteCard(
                        title = p.optString("title", "Урок пройден!"),
                        subtitle = p.optString("subtitle", ""),
                        buttonLabel = p.optString("button_label", "Продолжить"),
                        primaryAction = actionJson?.let {
                            LessonPrimaryAction(
                                type = actionType,
                                label = it.optString("label", "Собрать этого агента"),
                            )
                        },
                    )
                }
            }
            "switch" -> DrivenUiCard.SwitchCard(
                label = p.optString("label", "Переключатель"),
                description = p.optString("description", ""),
                action = p.optString("action", ""),
                initialOn = p.optBoolean("initial", false),
            )
            "notification_permission" -> DrivenUiCard.NotificationPermissionCard(
                title = p.optString("title", "Уведомления"),
                description = p.optString("description", "Разреши уведомления, чтобы получать напоминания."),
                buttonLabel = p.optString("button_label", "Разрешить уведомления"),
            )
            "reminder" -> {
                val id = p.optString("heartbeat_id", "")
                if (id.isBlank()) DrivenUiCard.ErrorCard("reminder: нет heartbeat_id")
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
            else -> DrivenUiCard.ErrorCard("Неизвестный шаблон: $template")
        }
    } catch (e: Exception) {
        DrivenUiCard.ErrorCard("Ошибка парсинга шаблона: ${e.localizedMessage}")
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
            appendLine("_Нет задач_")
        } else {
            items.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.text}") }
        }
    }.trim()

    is DrivenUiCard.WeatherCard -> buildString {
        appendLine("**Погода: $city**")
        appendLine()
        appendLine("| День | Темп | Описание |")
        appendLine("| --- | --- | --- |")
        days.forEach { appendLine("| ${it.dayName} | ${it.temp} | ${it.text} |") }
    }.trim()

    is DrivenUiCard.TimerCard -> "**$label** — таймер на $durationSec сек."

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
        appendLine("**Форма**")
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
        appendLine("**Проверка**")
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
        appendLine("**$label** _[${if (initialOn) "вкл" else "выкл"}]_")
        if (description.isNotBlank()) appendLine(description)
    }.trim()

    is DrivenUiCard.NotificationPermissionCard -> buildString {
        appendLine("**$title**")
        if (description.isNotBlank()) appendLine(description)
        appendLine("_[$buttonLabel]_")
    }.trim()

    // Живая карточка: в markdown-режиме (виджеты отключены) статус БД не подтянуть — только заглушка.
    is DrivenUiCard.ReminderCard -> "⏰ _Напоминание (id=$heartbeatId)_"

    is DrivenUiCard.ImageCard -> buildString {
        val src = asset.ifBlank { url }
        appendLine("🖼️ ${caption.ifBlank { "Изображение" }}")
        if (src.isNotBlank()) appendLine("_($src)_")
    }.trim()

    is DrivenUiCard.LessonVideoCard -> "🎥 ${instruction.ifBlank { "Сними короткое видео" }}"

    is DrivenUiCard.LessonAnimationCard -> "🎬 ${caption.ifBlank { "Анимация урока" }}"

    is DrivenUiCard.RevealCard -> buildString {
        appendLine("**$question**")
        appendLine("> $answer")
    }.trim()

    is DrivenUiCard.ConceptCard -> buildString {
        appendLine("**$term** — $definition")
        if (example.isNotBlank()) appendLine("_Пример: ${example}_")
    }.trim()

    is DrivenUiCard.ExampleCard -> buildString {
        if (label.isNotBlank()) appendLine("**$label**")
        parts.forEach { p ->
            val note = if (p.note.isNotBlank()) " — _${p.note}_" else ""
            appendLine("- ${if (p.role.isNotBlank()) "[${p.role}] " else ""}${p.text}$note")
        }
    }.trim()

    is DrivenUiCard.FillCard -> "**Заполни пропуск:** ${template.replace("___", "_____")}"

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
