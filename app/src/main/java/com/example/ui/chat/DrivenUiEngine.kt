package com.aiopenpocket.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Defined structures of Templates
sealed class DrivenUiCard {
    data class TodoList(val title: String, val items: List<TodoItem>) : DrivenUiCard()
    data class WeatherCard(val city: String, val days: List<WeatherDay>) : DrivenUiCard()
    data class TimerCard(val label: String, val durationSec: Int) : DrivenUiCard()
    data class TableCard(val headers: List<String>, val rows: List<List<String>>) : DrivenUiCard()
    data class ChartBarCard(val title: String, val labels: List<String>, val values: List<Float>) : DrivenUiCard()
    data class FormCard(val fields: List<FormField>, val submitLabel: String) : DrivenUiCard()
    data class ChoiceCard(val question: String, val options: List<String>, val answerIndex: Int) : DrivenUiCard()
    data class LessonCompleteCard(val title: String, val subtitle: String, val buttonLabel: String) : DrivenUiCard()
    data class ErrorCard(val message: String) : DrivenUiCard()
}

data class TodoItem(val text: String, var done: Boolean)
data class WeatherDay(val dayName: String, val temp: String, val text: String, val icon: String)
data class FormField(val name: String, val label: String, val placeholder: String, val type: String, var value: String = "")

object DrivenUiEngine {

    fun parseJsonString(specString: String): DrivenUiCard {
        return try {
            val root = JSONObject(specString)
            val template = root.optString("template", "")
            val params = root.optJSONObject("params") ?: JSONObject()

            when (template) {
                "todo_list" -> {
                    val title = params.optString("title", "Список дел")
                    val itemsJson = params.optJSONArray("items") ?: JSONArray()
                    val items = mutableListOf<TodoItem>()
                    for (i in 0 until itemsJson.length()) {
                        val obj = itemsJson.optJSONObject(i) ?: JSONObject()
                        items.add(
                            TodoItem(
                                text = obj.optString("item_text", ""),
                                done = obj.optBoolean("item_done", false)
                            )
                        )
                    }
                    DrivenUiCard.TodoList(title, items)
                }
                "weather_card" -> {
                    val city = params.optString("city", "Погода")
                    val daysJson = params.optJSONArray("days") ?: JSONArray()
                    val days = mutableListOf<WeatherDay>()
                    for (i in 0 until daysJson.length()) {
                        val obj = daysJson.optJSONObject(i) ?: JSONObject()
                        days.add(
                            WeatherDay(
                                dayName = obj.optString("day", ""),
                                temp = obj.optString("temp", ""),
                                text = obj.optString("text", "Ясно"),
                                icon = obj.optString("icon", "clear")
                            )
                        )
                    }
                    DrivenUiCard.WeatherCard(city, days)
                }
                "timer" -> {
                    val label = params.optString("label", "Таймер")
                    val durationSec = params.optInt("duration_sec", 60)
                    DrivenUiCard.TimerCard(label, durationSec)
                }
                "table" -> {
                    val headersJson = params.optJSONArray("headers") ?: JSONArray()
                    val headers = List(headersJson.length()) { headersJson.optString(it, "") }
                    val rowsJson = params.optJSONArray("rows") ?: JSONArray()
                    val rows = mutableListOf<List<String>>()
                    for (i in 0 until rowsJson.length()) {
                        val rowArray = rowsJson.optJSONArray(i) ?: JSONArray()
                        rows.add(List(rowArray.length()) { rowArray.optString(it, "") })
                    }
                    DrivenUiCard.TableCard(headers, rows)
                }
                "chart_bar" -> {
                    val title = params.optString("title", "График")
                    val labelsJson = params.optJSONArray("labels") ?: JSONArray()
                    val labels = List(labelsJson.length()) { labelsJson.optString(it, "") }
                    val valuesJson = params.optJSONArray("values") ?: JSONArray()
                    val values = List(valuesJson.length()) { valuesJson.optDouble(it, 0.0).toFloat() }
                    DrivenUiCard.ChartBarCard(title, labels, values)
                }
                "form" -> {
                    val submitLabel = params.optString("submit_label", "Отправить")
                    val fieldsJson = params.optJSONArray("fields") ?: JSONArray()
                    val fields = mutableListOf<FormField>()
                    for (i in 0 until fieldsJson.length()) {
                        val obj = fieldsJson.optJSONObject(i) ?: JSONObject()
                        fields.add(
                            FormField(
                                name = obj.optString("name", "field"),
                                label = obj.optString("label", "Поле"),
                                placeholder = obj.optString("placeholder", ""),
                                type = obj.optString("type", "text")
                            )
                        )
                    }
                    DrivenUiCard.FormCard(fields, submitLabel)
                }
                "choice" -> {
                    val question = params.optString("question", "")
                    val optionsJson = params.optJSONArray("options") ?: JSONArray()
                    val options = List(optionsJson.length()) { optionsJson.optString(it, "") }
                    val answerIndex = if (params.has("answer")) params.optInt("answer", -1) else -1
                    DrivenUiCard.ChoiceCard(question, options, answerIndex)
                }
                "lesson_complete" -> {
                    val title = params.optString("title", "Урок пройден!")
                    val subtitle = params.optString("subtitle", "")
                    val buttonLabel = params.optString("button_label", "Продолжить")
                    DrivenUiCard.LessonCompleteCard(title, subtitle, buttonLabel)
                }
                else -> DrivenUiCard.ErrorCard("Неизвестный шаблон: $template")
            }
        } catch (e: Exception) {
            DrivenUiCard.ErrorCard("Ошибка парсинга шаблона: ${e.localizedMessage}")
        }
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

    is DrivenUiCard.LessonCompleteCard -> buildString {
        appendLine("**✓ $title**")
        if (subtitle.isNotBlank()) appendLine(subtitle)
        appendLine("_[$buttonLabel]_")
    }.trim()

    is DrivenUiCard.ErrorCard -> "> ⚠️ $message"
}

@Composable
fun RenderDrivenUiCard(
    spec: String,
    onFormSubmitted: (String) -> Unit = {},
    onChoiceSelected: (String) -> Unit = {},
    onLessonComplete: () -> Unit = {}
) {
    val card = remember(spec) { DrivenUiEngine.parseJsonString(spec) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp)
    ) {
        when (card) {
            is DrivenUiCard.TodoList -> TodoListCardView(card)
            is DrivenUiCard.WeatherCard -> WeatherCardView(card)
            is DrivenUiCard.TimerCard -> TimerCardView(card)
            is DrivenUiCard.TableCard -> TableCardView(card)
            is DrivenUiCard.ChartBarCard -> ChartBarCardView(card)
            is DrivenUiCard.FormCard -> FormCardView(card, onFormSubmitted)
            is DrivenUiCard.ChoiceCard -> ChoiceCardView(card, onChoiceSelected)
            is DrivenUiCard.LessonCompleteCard -> LessonCompleteCardView(card, onLessonComplete)
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

@Composable
fun TodoListCardView(card: DrivenUiCard.TodoList) {
    var itemsState = remember { mutableStateListOf<TodoItem>().apply { addAll(card.items) } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.PlaylistAddCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = card.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.WbSunny,
                contentDescription = null,
                tint = Color(0xFFF9D976),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Прогноз для: ${card.city}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f))

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
                text = String.format("%02d:%02d", minutes, seconds),
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
                    val valueText = card.values.getOrNull(index)?.toString() ?: ""
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
    val fieldsState = remember { mutableStateListOf<FormField>().apply { addAll(card.fields) } }

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
    var selected by remember { mutableStateOf<Int?>(null) }
    val correctGreen = Color(0xFF4CAF50)

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
            val isCorrect = card.answerIndex >= 0 && i == card.answerIndex
            val isPicked = selected == i
            val borderColor = when {
                !answered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                isCorrect -> correctGreen
                isPicked -> MaterialTheme.colorScheme.error
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
                    answered && isPicked -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun LessonCompleteCardView(
    card: DrivenUiCard.LessonCompleteCard,
    onComplete: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
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
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
            Text(card.buttonLabel)
        }
    }
}
