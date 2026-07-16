package com.aiopenpocket.ui.chat

// @trace driven-ui-cards-0418d0
// @trace user-agent-builder-848153

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import com.aiopenpocket.ui.roadmap.QuizQuestion
import com.aiopenpocket.ui.roadmap.components.QuizContent
import com.aiopenpocket.ui.roadmap.components.stableShuffledIndices
import java.util.Locale

private val CardSuccessGreen = Color(0xFF4CAF50)

// Светлая подложка под картинками урока. SVG-диаграммы нарисованы «под светлый фон» (серые подписи
// #9aa0a6 + светлый текст на цветных заливках). После glass-редизайна пузырь ассистента стал
// прозрачным поверх тёмной ауроры — и прозрачные SVG «пропадали». Фиксированный светлый фон под
// картинкой возвращает им читаемость в любой теме (тема к SVG не применяется, см. lesson-format.md).
private val LessonImageBacking = Color(0xFFF4F4F7)

// Тёмная тема: карточки урока рисуем светлым серебром с тёмным текстом (а не тёмным surfaceVariant
// «тёмное-на-тёмном»). LessonCardTheme переопределяет surface-семейство схемы для поддерева карточки,
// чтобы существующие onSurfaceVariant/outline/surface внутри сами стали «под светлую карточку».
private val LessonCardSilver = Color(0xFFDCDCE4)   // фон карточки (surfaceVariant)
private val LessonCardPanel = Color(0xFFCBCDD8)    // внутренние панели (surface)
private val LessonCardInk = Color(0xFF2A2730)      // текст (onSurface/onSurfaceVariant)
private val LessonCardOutline = Color(0xFF6B6B75)  // подписи и разделители (outline)

/** В тёмной теме подменяет surface-семейство схемы на светлое серебро для поддерева карточки урока. */
@Composable
private fun LessonCardTheme(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            surface = LessonCardPanel,
            surfaceVariant = LessonCardSilver,
            onSurface = LessonCardInk,
            onSurfaceVariant = LessonCardInk,
            outline = LessonCardOutline,
        ),
        content = content,
    )
}

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
    data class ImageCard(val asset: String, val url: String, val caption: String) : DrivenUiCard()
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

@Composable
fun RenderDrivenUiCard(
    // Готовая карточка, а не JSON-спек: пузырь уже парсил spec, решая interactive/markdown —
    // второй парс того же JSON на главном потоке при первой композиции итема был лишним.
    card: DrivenUiCard,
    onFormSubmitted: (String) -> Unit = {},
    onChoiceSelected: (String) -> Unit = {},
    onLessonComplete: () -> Unit = {},
    onLessonQuizComplete: () -> Unit = {},
    // Капстоун Академии (Task 23): тап по CTA lesson_complete-карточки. Zero-arg — какой
    // AgentSourceContext это значит, решает вызывающий (ChatScreen), а не движок карточек.
    onOpenAgentBuilder: () -> Unit = {},
    onAction: (String, Boolean) -> Unit = { _, _ -> },
    observeHeartbeat: (String) -> Flow<HeartbeatEntity?> = { kotlinx.coroutines.flow.flowOf(null) },
    onCancelHeartbeat: (String) -> Unit = {},
    /** true → карточка урока: в тёмной теме рисуем светлым серебром с тёмным текстом. */
    lessonCard: Boolean = false,
) {
    val useLightCard = lessonCard && isSystemInDarkTheme()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (useLightCard) LessonCardSilver
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .padding(16.dp)
    ) {
        LessonCardTheme(useLightCard) {
        // Контент-цвет surface явно — как сделал бы Surface(color = surfaceVariant): тексты без явного
        // цвета (ответ reveal/fill, определение concept, пункты sort/match) не берут ~чёрный дефолт.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
        when (card) {
            is DrivenUiCard.TodoList -> TodoListCardView(card)
            is DrivenUiCard.WeatherCard -> WeatherCardView(card)
            is DrivenUiCard.TimerCard -> TimerCardView(card)
            is DrivenUiCard.TableCard -> TableCardView(card)
            is DrivenUiCard.ChartBarCard -> ChartBarCardView(card)
            is DrivenUiCard.FormCard -> FormCardView(card, onFormSubmitted)
            is DrivenUiCard.ChoiceCard -> ChoiceCardView(card, onChoiceSelected)
            is DrivenUiCard.LessonQuizCard -> LessonQuizCardView(card, onLessonQuizComplete)
            is DrivenUiCard.LessonCompleteCard -> LessonCompleteCardView(card, onLessonComplete, onOpenAgentBuilder)
            is DrivenUiCard.SwitchCard -> SwitchCardView(card, onAction)
            is DrivenUiCard.NotificationPermissionCard -> NotificationPermissionCardView(card)
            is DrivenUiCard.ReminderCard -> ReminderCardView(card, observeHeartbeat, onCancelHeartbeat)
            is DrivenUiCard.ImageCard -> ImageCardView(card)
            is DrivenUiCard.RevealCard -> RevealCardView(card)
            is DrivenUiCard.ConceptCard -> ConceptCardView(card)
            is DrivenUiCard.ExampleCard -> ExampleCardView(card)
            is DrivenUiCard.FillCard -> FillCardView(card, onLessonQuizComplete)
            is DrivenUiCard.SortCard -> SortCardView(card, onLessonQuizComplete)
            is DrivenUiCard.MatchCard -> MatchCardView(card, onLessonQuizComplete)
            is DrivenUiCard.ScenarioCard -> ScenarioCardView(card, onLessonQuizComplete)
            is DrivenUiCard.ErrorCard -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = "Ошибка",
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = card.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        }
        }
    }
}

@Composable
private fun CardTitleRow(icon: ImageVector, title: String, iconTint: Color? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint ?: MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))
}

@Composable
fun TodoListCardView(card: DrivenUiCard.TodoList) {
    val itemsState = remember(card) { mutableStateListOf<TodoItem>().apply { addAll(card.items) } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitleRow(icon = Icons.Default.PlaylistAddCheck, title = card.title)
        CardDivider()

        if (itemsState.isEmpty()) {
            Text(
                text = "Нет задач",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            itemsState.forEachIndexed { index, item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            itemsState[index] = item.copy(done = !item.done)
                        }
                        .padding(vertical = 4.dp, horizontal = 8.dp)
                ) {
                    Checkbox(
                        checked = item.done,
                        onCheckedChange = { isChecked ->
                            itemsState[index] = item.copy(done = isChecked)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun WeatherCardView(card: DrivenUiCard.WeatherCard) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CardTitleRow(
            icon = Icons.Default.WbSunny,
            title = "Прогноз для: ${card.city}",
            iconTint = Color(0xFFF9D976),
        )
        CardDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            card.days.forEach { day ->
                Card(
                    modifier = Modifier.weight(1f),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = day.dayName,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Icon(
                            imageVector = when (day.icon.lowercase()) {
                                "cloud", "cloudy" -> Icons.Default.Cloud
                                "rain" -> Icons.Default.BeachAccess
                                "sunny", "clear" -> Icons.Default.WbSunny
                                else -> Icons.Default.WbCloudy
                            },
                            contentDescription = day.text,
                            tint = if (day.icon == "sunny" || day.icon == "clear") Color(0xFFF9D976) else MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = day.temp,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = day.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TimerCardView(card: DrivenUiCard.TimerCard) {
    var timeLeft by remember { mutableStateOf(card.durationSec) }
    var isRunning by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning, timeLeft) {
        if (isRunning && timeLeft > 0) {
            delay(1000L)
            timeLeft--
        } else if (timeLeft == 0) {
            isRunning = false
        }
    }

    val minutes = timeLeft / 60
    val seconds = timeLeft % 60
    val progress = if (card.durationSec > 0) timeLeft.toFloat() / card.durationSec.toFloat() else 0f

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = card.label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(100.dp)
        ) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize(),
                strokeWidth = 6.dp,
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            Text(
                text = String.format(Locale.US, "%02d:%02d", minutes, seconds),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(
                onClick = { isRunning = !isRunning },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(if (isRunning) "Пауза" else "Старт")
            }

            TextButton(
                onClick = {
                    isRunning = false
                    timeLeft = card.durationSec
                }
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Сброс")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Сброс")
            }
        }
    }
}

@Composable
fun TableCardView(card: DrivenUiCard.TableCard) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (card.headers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                card.headers.forEach { header ->
                    Text(
                        text = header,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        card.rows.forEachIndexed { index, row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (index % 2 == 0) Color.Transparent
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun ChartBarCardView(card: DrivenUiCard.ChartBarCard) {
    val barColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val textStyle = MaterialTheme.typography.bodySmall

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        if (card.values.isNotEmpty()) {
            val maxVal = remember(card.values) { card.values.maxOrNull() ?: 1.0f }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .padding(horizontal = 8.dp)
            ) {
                val canvasWidth = size.width
                val canvasHeight = size.height

                val paddingBottom = 40f
                val paddingLeft = 10f
                val chartHeight = canvasHeight - paddingBottom

                val numBars = card.values.size
                val barWidth = (canvasWidth - paddingLeft) / numBars

                // Draw horizontal axis
                drawLine(
                    color = axisColor,
                    start = Offset(0f, chartHeight),
                    end = Offset(canvasWidth, chartHeight),
                    strokeWidth = 4f
                )

                card.values.forEachIndexed { i, value ->
                    val progress = if (maxVal > 0) value / maxVal else 0f
                    val barHeight = chartHeight * progress

                    val left = paddingLeft + i * barWidth + (barWidth * 0.15f)
                    val right = paddingLeft + (i + 1) * barWidth - (barWidth * 0.15f)

                    drawRect(
                        color = barColor,
                        topLeft = Offset(left, chartHeight - barHeight),
                        size = Size(right - left, barHeight)
                    )
                }
            }

            // Print values & labels text
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                card.labels.forEachIndexed { index, label ->
                    val v = card.values.getOrNull(index) ?: 0f
                    val valueText = if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = valueText,
                            style = textStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = label,
                            style = textStyle,
                            fontSize = 11.sp,
                            maxLines = 1,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FormCardView(
    card: DrivenUiCard.FormCard,
    onFormSubmitted: (String) -> Unit
) {
    val fieldsState = remember(card) { mutableStateListOf<FormField>().apply { addAll(card.fields) } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Форма ввода данных",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

        fieldsState.forEachIndexed { index, field ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = field.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = field.value,
                    onValueChange = { newValue ->
                        fieldsState[index] = field.copy(value = newValue)
                    },
                    placeholder = { Text(field.placeholder) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("form_input_${field.name}")
                )
            }
        }

        Button(
            onClick = {
                val results = JSONObject()
                fieldsState.forEach {
                    results.put(it.name, it.value)
                }
                onFormSubmitted(results.toString())
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("form_submit_button")
        ) {
            Icon(imageVector = Icons.Default.Send, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(card.submitLabel)
        }
    }
}

@Composable
fun ChoiceCardView(
    card: DrivenUiCard.ChoiceCard,
    onSelected: (String) -> Unit
) {
    var selected by remember(card) { mutableStateOf<Int?>(null) }
    val correctGreen = CardSuccessGreen

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.question.isNotBlank()) {
            Text(
                text = card.question,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        card.options.forEachIndexed { i, option ->
            val answered = selected != null
            // answerIndex < 0 — правильный вариант не задан (например, открытый опрос без "верного"
            // ответа): не подсвечиваем выбор как неверный, см. f646e64 для того же класса бага.
            val hasKey = card.answerIndex >= 0
            val isCorrect = hasKey && i == card.answerIndex
            val isPicked = selected == i
            val borderColor = when {
                !answered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                isCorrect -> correctGreen
                hasKey && isPicked -> MaterialTheme.colorScheme.error
                isPicked -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                    .clickable(enabled = selected == null) {
                        selected = i
                        onSelected(option)
                    }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = option, style = MaterialTheme.typography.bodyLarge)
                when {
                    answered && isCorrect -> Icon(Icons.Default.Check, null, tint = correctGreen)
                    answered && hasKey && isPicked -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun LessonQuizCardView(
    card: DrivenUiCard.LessonQuizCard,
    onAllAnswered: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Закрепим пройденное",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QuizContent(
            questions = card.questions,
            onAllAnswered = onAllAnswered,
        )
    }
}

@Composable
fun LessonCompleteCardView(
    card: DrivenUiCard.LessonCompleteCard,
    onComplete: () -> Unit,
    onOpenAgentBuilder: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = CardSuccessGreen,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (card.subtitle.isNotBlank()) {
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline
            )
        }
        // Капстоун Академии (Task 23): рендерится, ТОЛЬКО когда саму карточку собрал
        // LessonChatController.postCompletion для agent_blueprint — обычные уроки primaryAction не
        // задают, поэтому здесь ничего не появляется и старое поведение не меняется.
        card.primaryAction?.let { action ->
            Button(onClick = onOpenAgentBuilder, modifier = Modifier.fillMaxWidth()) {
                Text(action.label)
            }
        }
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
            Text(card.buttonLabel)
        }
    }
}

@Composable
fun SwitchCardView(
    card: DrivenUiCard.SwitchCard,
    onAction: (String, Boolean) -> Unit
) {
    var checked by remember(card) { mutableStateOf(card.initialOn) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = card.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (card.description.isNotBlank()) {
                Text(
                    text = card.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = { isOn ->
                checked = isOn
                onAction(card.action, isOn)
            }
        )
    }
}

/**
 * Живая карточка heartbeat'а: подписывается на строку в Room по [DrivenUiCard.ReminderCard.heartbeatId]
 * и отражает её (расписание/статус/последний результат) в реальном времени — без ререндера всего
 * сообщения. Кнопка отмены показывается, только пока напоминание ещё "scheduled".
 */
@Composable
fun ReminderCardView(
    card: DrivenUiCard.ReminderCard,
    observeHeartbeat: (String) -> Flow<HeartbeatEntity?>,
    onCancel: (String) -> Unit,
) {
    val hb by remember(card.heartbeatId) { observeHeartbeat(card.heartbeatId) }
        .collectAsState(initial = null)
    val current = hb ?: run {
        Text(
            text = "Напоминание не найдено",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        return
    }
    val cancelled = current.status == "cancelled"
    val contentAlpha = if (cancelled) 0.5f else 1f

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().alpha(contentAlpha),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (current.mode == "agent") Icons.Default.SmartToy else Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "${formatSchedule(current)} · ${formatStatus(current)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        current.lastResult?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (current.status == "scheduled") {
            TextButton(onClick = { onCancel(card.heartbeatId) }) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Отменить")
            }
        }
    }
}

/**
 * Самодостаточная карточка запроса разрешения на уведомления: сама держит launcher и
 * отражает РЕАЛЬНОЕ состояние (areNotificationsEnabled), обновляя его на ON_RESUME —
 * никакого внешнего роутинга действий не требуется.
 */
@Composable
fun NotificationPermissionCardView(card: DrivenUiCard.NotificationPermissionCard) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var enabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Возврат из системного диалога/настроек → пересчитываем состояние, чтобы карточка не врала.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        if (card.description.isNotBlank()) {
            Text(
                text = card.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF4CAF50))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Уведомления включены", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // < 33: runtime-разрешения нет — ведём в системные настройки уведомлений.
                        openAppNotificationSettings(context)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(card.buttonLabel)
            }
        }
    }
}

/**
 * Картинка/диаграмма в шаге урока. Источник — бандл-ассет (пробуем ПЕРВЫМ, чтобы работало оффлайн),
 * с фоллбеком на сетевой URL, если ассета нет или он не загрузился. SVG и растр декодирует общий
 * Coil-синглтон приложения (SvgDecoder подключён в [com.aiopenpocket.AiOpenPocketApp.newImageLoader]).
 * Раньше здесь собирался СВОЙ ImageLoader на каждый экземпляр карточки — его кэш умирал вместе с
 * итемом ленты, и при возврате пузыря в вьюпорт картинка грузилась заново (пустой пузырь → скачок).
 */
@Composable
fun ImageCardView(card: DrivenUiCard.ImageCard) {
    val context = LocalContext.current
    val assetData = card.asset.takeIf { it.isNotBlank() }?.let { "file:///android_asset/$it" }
    val urlData = card.url.takeIf { it.isNotBlank() }
    // Ассет первым; при ошибке загрузки переключаемся на URL (один раз).
    var useFallback by remember(card) { mutableStateOf(assetData == null) }
    val data = if (!useFallback) (assetData ?: urlData) else urlData

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (data == null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.BrokenImage, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                Text(
                    text = "Нет изображения",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(data).crossfade(true).build(),
                contentDescription = card.caption.ifBlank { null },
                // Фиксированный аспект: высота пузыря известна ДО загрузки картинки, поэтому лента
                // не «раздувается» и не прыгает, когда картинка доехала (раньше — FillWidth без
                // высоты: пузырь рождался схлопнутым). Fit — схемы/диаграммы не обрезаем, возможен
                // леттербокс по краям.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    // Светлая подложка: прозрачные SVG рисовались «под светлый фон», а после glass
                    // пузырь стал прозрачным над тёмной ауророй — без неё диаграмма пропадала.
                    .background(LessonImageBacking),
                onError = {
                    // Ассет не загрузился — пробуем сетевой URL, если он задан и ещё не пробовали.
                    if (!useFallback && assetData != null && urlData != null) useFallback = true
                },
            )
        }
        if (card.caption.isNotBlank()) {
            Text(
                text = card.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** "reveal": вопрос, по кнопке раскрывается ответ. Шаг продвигается кнопкой «Дальше». */
@Composable
fun RevealCardView(card: DrivenUiCard.RevealCard) {
    // rememberSaveable: раскрытие переживает рециклинг элемента в LazyColumn и поворот
    // (обычный remember сбрасывал карточку в «закрыто» при скролле за экран и обратно).
    var revealed by rememberSaveable(card) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = card.question,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = revealed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lightbulb, null, tint = CardSuccessGreen, modifier = Modifier.size(20.dp))
                Text(text = card.answer, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (!revealed) {
            Button(onClick = { revealed = true }) {
                Icon(Icons.Default.Visibility, null)
                Spacer(Modifier.width(8.dp))
                Text("Показать ответ")
            }
        }
    }
}

/** "concept": термин, определение, пример. Статичная карточка. */
@Composable
fun ConceptCardView(card: DrivenUiCard.ConceptCard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitleRow(icon = Icons.Default.School, title = card.term)
        CardDivider()
        Text(text = card.definition, style = MaterialTheme.typography.bodyMedium)
        if (card.example.isNotBlank()) {
            Text(
                text = "Пример: ${card.example}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** "example": заголовок и разбор на части (текст + роль-бейдж + заметка). Статичная карточка. */
@Composable
fun ExampleCardView(card: DrivenUiCard.ExampleCard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.label.isNotBlank()) {
            Text(
                text = card.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardDivider()
        }
        card.parts.forEach { part ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (part.role.isNotBlank()) {
                    Text(
                        text = part.role.uppercase(Locale.getDefault()),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(text = part.text, style = MaterialTheme.typography.bodyMedium)
                if (part.note.isNotBlank()) {
                    Text(
                        text = part.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** "fill": заполни пропуск. Правильный ввод продвигает урок (как пройденный квиз). */
@Composable
fun FillCardView(card: DrivenUiCard.FillCard, onSolved: () -> Unit) {
    // rememberSaveable: решённость (и черновик) переживают рециклинг/поворот. Иначе карточка
    // сбрасывалась в нерешённую, и повторное решение ЕЩЁ РАЗ дёргало onSolved — курсор урока
    // прыгал на лишний шаг. onSolved зовётся только на переходе solved=false→true, поэтому
    // восстановленное solved=true повторного продвижения не даёт.
    var input by rememberSaveable(card) { mutableStateOf("") }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var wrong by remember(card) { mutableStateOf(false) }

    fun check() {
        val a = if (card.caseSensitive) input.trim() else input.trim().lowercase(Locale.getDefault())
        val b = if (card.caseSensitive) card.answer.trim() else card.answer.trim().lowercase(Locale.getDefault())
        if (a == b && a.isNotEmpty()) { solved = true; wrong = false; onSolved() } else { wrong = true }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = card.template.replace("___", "  _____  "),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (solved) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Check, null, tint = CardSuccessGreen)
                Text(card.answer, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; wrong = false },
                singleLine = true,
                isError = wrong,
                placeholder = { Text("Впиши пропущенное слово") },
                modifier = Modifier.fillMaxWidth(),
            )
            if (wrong) {
                Text("Не то — попробуй ещё", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { check() }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Проверить")
            }
        }
    }
}

/** "sort": расставь элементы стрелками вверх/вниз. Верный порядок продвигает урок. */
@Composable
fun SortCardView(card: DrivenUiCard.SortCard, onSolved: () -> Unit) {
    // rememberSaveable: расстановка и решённость переживают рециклинг элемента в LazyColumn и
    // поворот. Раньше remember сбрасывал карточку в нерешённую, и повторное решение ЕЩЁ РАЗ дёргало
    // onSolved — курсор урока прыгал на лишний шаг. onSolved зовётся только на переходе
    // solved=false→true, поэтому восстановленное solved=true повторного продвижения не даёт.
    // Порядок храним индексами: дубликаты строк в items не путают восстановление.
    val order = rememberSaveable(
        card,
        saver = listSaver<SnapshotStateList<Int>, Int>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { card.items.indices.toList().toMutableStateList() }
    // correct должен быть полной перестановкой индексов items. Если он битый (вне диапазона, дубли,
    // не та длина — частый брак LLM-карточки или авторской ошибки урока), деградируем к исходному
    // порядку, чтобы шаг остался решаемым по «Проверить», а не залип навсегда.
    val target = remember(card) {
        val n = card.items.size
        val isPermutation = card.correct.size == n && card.correct.toSet() == (0 until n).toSet()
        if (isPermutation) card.correct.map { card.items[it] } else card.items
    }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var wrong by remember(card) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.instruction.isNotBlank()) {
            Text(card.instruction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        order.forEachIndexed { i, itemIndex ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(8.dp))
                Text(card.items[itemIndex], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (!solved) {
                    IconButton(onClick = {
                        if (i > 0) { val t = order[i - 1]; order[i - 1] = order[i]; order[i] = t; wrong = false }
                    }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Вверх") }
                    IconButton(onClick = {
                        if (i < order.size - 1) { val t = order[i + 1]; order[i + 1] = order[i]; order[i] = t; wrong = false }
                    }, enabled = i < order.size - 1) { Icon(Icons.Default.KeyboardArrowDown, "Вниз") }
                }
            }
        }
        if (solved) {
            if (card.explain.isNotBlank()) {
                Text("✓ ${card.explain}", style = MaterialTheme.typography.bodySmall, color = CardSuccessGreen)
            }
        } else {
            if (wrong) {
                Text("Пока не тот порядок — переставь и проверь снова", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    // Сравниваем отображаемые строки (а не индексы): при дубликатах в items любой
                    // визуально совпадающий порядок засчитывается — как и раньше.
                    if (order.map { card.items[it] } == target) { solved = true; wrong = false; onSolved() } else wrong = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Проверить") }
        }
    }
}

/** "match": сопоставь термины и определения тапами. Полное верное сопоставление продвигает урок. */
@Composable
fun MatchCardView(card: DrivenUiCard.MatchCard, onSolved: () -> Unit) {
    val terms = card.pairs.map { it.term }
    // Определения показываем в перемешанном (стабильном) порядке. Раньше был сдвиг на 1 — верное
    // определение почти всегда оказывалось «следующим по списку» и сопоставление угадывалось.
    // avoidIdentity: исходный порядок подсказал бы все пары разом.
    val displayDefOrder = remember(card) {
        val seed = card.pairs.fold(0) { acc, p -> (acc * 31 + p.term.hashCode()) * 31 + p.def.hashCode() }
        stableShuffledIndices(card.pairs.size, seed, avoidIdentity = true)
    }
    // rememberSaveable: сопоставление и решённость переживают рециклинг/поворот (см. SortCardView —
    // тот же класс бага с повторным onSolved).
    val assign = rememberSaveable(
        card,
        saver = listSaver<SnapshotStateMap<Int, Int>, Int>(
            save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
            restore = { flat ->
                mutableStateMapOf<Int, Int>().apply {
                    var i = 0
                    while (i + 1 < flat.size) {
                        put(flat[i], flat[i + 1])
                        i += 2
                    }
                }
            },
        ),
    ) { mutableStateMapOf() } // termIndex -> исходный индекс пары (он же верный термин)
    var selectedTerm by remember(card) { mutableStateOf<Int?>(null) }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var wrong by remember(card) { mutableStateOf(false) }
    val correctGreen = CardSuccessGreen

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.instruction.isNotBlank()) {
            Text(card.instruction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Термины
        terms.forEachIndexed { ti, term ->
            val assignedDef = assign[ti]?.let { card.pairs[it].def }
            val isSel = selectedTerm == ti
            val border = when {
                solved && assign[ti] == ti -> correctGreen
                solved -> MaterialTheme.colorScheme.error
                isSel -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.5.dp, border, RoundedCornerShape(10.dp))
                    .clickable(enabled = !solved) { selectedTerm = if (isSel) null else ti }
                    .padding(12.dp),
            ) {
                Text(term, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    text = assignedDef ?: "выбери →",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (assignedDef != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
        CardDivider()
        Text("Определения:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        // Определения-кнопки: тап назначает выбранному термину
        displayDefOrder.forEach { di ->
            val used = assign.containsValue(di)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (used) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    )
                    .clickable(enabled = !solved && selectedTerm != null) {
                        val t = selectedTerm
                        if (t != null) {
                            // снять этот def с прежнего термина, затем назначить
                            assign.entries.firstOrNull { it.value == di }?.let { assign.remove(it.key) }
                            assign[t] = di
                            selectedTerm = null
                            wrong = false
                        }
                    }
                    .padding(12.dp),
            ) {
                Text(card.pairs[di].def, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (used) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        if (solved) {
            Text("✓ Верно!", style = MaterialTheme.typography.bodySmall, color = correctGreen)
        } else {
            if (wrong) {
                Text("Не всё совпало — поправь пары", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    if (assign.size == terms.size && assign.all { it.key == it.value }) { solved = true; wrong = false; onSolved() } else wrong = true
                },
                enabled = assign.size == terms.size,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Проверить") }
        }
    }
}

/** "scenario": ситуация + один вопрос с вариантами. Ответ продвигает урок. */
@Composable
fun ScenarioCardView(card: DrivenUiCard.ScenarioCard, onSolved: () -> Unit) {
    // rememberSaveable: ответ переживает рециклинг/поворот — повторный тап по «сброшенной» карточке
    // раньше ЕЩЁ РАЗ дёргал onSolved и толкал урок на лишний шаг. -1 = ещё не отвечено
    // (Int? автосейвер не сохраняет). Храним ИСХОДНЫЙ индекс варианта — сверка с answerIndex
    // и восстановление не зависят от порядка показа.
    var selected by rememberSaveable(card) { mutableStateOf(-1) }
    // Варианты — в перемешанном (стабильном) порядке: в авторских JSON правильный всегда первый.
    val displayOrder = remember(card) {
        val seed = (card.situation.hashCode() * 31 + card.question.hashCode()) * 31 +
            card.options.fold(0) { acc, o -> acc * 31 + o.hashCode() }
        stableShuffledIndices(card.options.size, seed)
    }
    val correctGreen = CardSuccessGreen

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.situation.isNotBlank()) {
            Text(
                text = card.situation,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (card.question.isNotBlank()) {
            Text(card.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        displayOrder.forEach { i ->
            val option = card.options[i]
            val answered = selected >= 0
            // answerIndex < 0 — правильный вариант неизвестен (не задан/битый): не подсвечиваем
            // ничего как «верное/неверное», чтобы не вводить в заблуждение ложным ключом.
            val hasKey = card.answerIndex >= 0
            val isCorrect = hasKey && i == card.answerIndex
            val isPicked = selected == i
            val border = when {
                !answered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                isCorrect -> correctGreen
                hasKey && isPicked -> MaterialTheme.colorScheme.error
                isPicked -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, border, RoundedCornerShape(12.dp))
                    .clickable(enabled = selected < 0) {
                        selected = i
                        // Ответил — двигаемся дальше независимо от правильности: виден верный вариант + пояснение.
                        onSolved()
                    }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                when {
                    answered && isCorrect -> Icon(Icons.Default.Check, null, tint = correctGreen)
                    answered && hasKey && isPicked -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (selected >= 0 && card.explain.isNotBlank()) {
            Text(card.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun openAppNotificationSettings(context: android.content.Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.fromParts("package", context.packageName, null))
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
