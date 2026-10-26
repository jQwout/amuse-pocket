package com.aiopenpocket.ui.chat.components

// @trace driven-ui-cards-0418d0
//
// Rendering of Driven-UI cards: RenderDrivenUiCard and all the *CardView, plus the lesson card's light
// theme with its palette. Separated from DrivenUiEngine.kt, which kept the card model and JSON parsing —
// previously ~1250 lines of Compose buried the spec parsing logic.

import com.aiopenpocket.ui.chat.DrivenUiCard
import com.aiopenpocket.ui.chat.ExamplePart
import com.aiopenpocket.ui.chat.formatSchedule
import com.aiopenpocket.ui.chat.formatStatus
import com.aiopenpocket.ui.chat.FormField
import com.aiopenpocket.ui.chat.LessonPrimaryAction
import com.aiopenpocket.ui.chat.MatchPair
import com.aiopenpocket.ui.chat.TodoItem
import com.aiopenpocket.ui.chat.WeatherDay
import android.content.Intent
import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.roadmap.components.QuizContent
import com.aiopenpocket.ui.roadmap.components.stableShuffledIndices
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

private val CardSuccessGreen = Color(0xFF4CAF50)

// A light backing under lesson images. The SVG diagrams are drawn "for a light background" (gray captions
// #9aa0a6 + light text on colored fills). After the glass redesign the assistant bubble became
// transparent over the dark aurora — and transparent SVGs "disappeared". A fixed light background under
// the image brings back their readability in any theme (the theme is not applied to SVGs, see
// lesson-format.md).
private val LessonImageBacking = Color(0xFFF4F4F7)

// Dark theme: lesson cards are drawn in light silver with dark text (rather than a dark surfaceVariant,
// "dark on dark"). LessonCardTheme overrides the scheme's surface family for the card's subtree, so the
// existing onSurfaceVariant/outline/surface inside become "for a light card" on their own.
private val LessonCardSilver = Color(0xFFDCDCE4)   // the card background (surfaceVariant)
private val LessonCardPanel = Color(0xFFCBCDD8)    // inner panels (surface)
private val LessonCardInk = Color(0xFF2A2730)      // text (onSurface/onSurfaceVariant)
private val LessonCardOutline = Color(0xFF6B6B75)  // captions and dividers (outline)
// Semantic quiz colors (fill/sort/match/scenario color "selected/wrong" through primary/error): the
// default PurpleAccent/CancelRed are made for a dark background and fade on light silver — we take dark
// saturated versions, readable on LessonCardSilver, with a light on-color for container uses.
private val LessonCardPrimary = Color(0xFF5B3FA6)  // primary (selected/right) — dark purple
private val LessonCardOnPrimary = Color(0xFFF3EEFF)
private val LessonCardError = Color(0xFFB3261E)    // error (wrong) — dark red (the M3 light error)
private val LessonCardOnError = Color(0xFFFFFFFF)
private val LessonCardSecondary = Color(0xFF544F63) // secondary (rare accent icons)

/** In the dark theme swaps the scheme's surface family for light silver in the lesson card's subtree. */
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
            primary = LessonCardPrimary,
            onPrimary = LessonCardOnPrimary,
            error = LessonCardError,
            onError = LessonCardOnError,
            secondary = LessonCardSecondary,
        ),
        content = content,
    )
}

@Composable
fun RenderDrivenUiCard(
    // A ready card rather than a JSON spec: the bubble has already parsed the spec deciding interactive/markdown
    // — a second parse of the same JSON on the main thread at the item's first composition was redundant.
    card: DrivenUiCard,
    onFormSubmitted: (String) -> Unit = {},
    onChoiceSelected: (String) -> Unit = {},
    onLessonComplete: () -> Unit = {},
    onLessonQuizComplete: () -> Unit = {},
    // The Academy capstone (Task 23): a tap on the CTA of the lesson_complete card. Zero-arg — which
    // AgentSourceContext it means is decided by the caller (ChatScreen), not by the card engine.
    onOpenAgentBuilder: () -> Unit = {},
    onAction: (String, Boolean) -> Unit = { _, _ -> },
    observeHeartbeat: (String) -> Flow<HeartbeatEntity?> = { kotlinx.coroutines.flow.flowOf(null) },
    onCancelHeartbeat: (String) -> Unit = {},
    /** true → a lesson card: in the dark theme it is drawn in light silver with dark text. */
    lessonCard: Boolean = false,
    /** Image prompts being redrawn "for me" right now — their button shows "Drawing…". */
    personalizingImages: Set<String> = emptySet(),
    onPersonalizeImage: (DrivenUiCard.ImageCard) -> Unit = {},
    /** The tasks of "video" steps whose clips the model is watching right now. */
    analyzingVideos: Set<String> = emptySet(),
    onLessonVideo: (videoPrompt: String, video: android.net.Uri) -> Unit = { _, _ -> },
) {
    // The gate is only lessonCard, not isSystemInDarkTheme(): the app's theme is chosen in Settings
    // (Light/Dark/System, see Theme.kt), so the system flag can disagree with what is on screen — and in a
    // forced dark theme a lesson card would stay dark on the dark aurora (exactly the unreadability this
    // code fixes). A lesson card is drawn light in any theme.
    val useLightCard = lessonCard
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
        // The surface content color is explicit — as Surface(color = surfaceVariant) would do: texts without an
        // explicit color (the reveal/fill answer, the concept definition, sort/match items) do not take the
        // ~black default.
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
            // The "Draw it for me" button — only for lesson images with a prompt: an image the agent drew in a
            // normal chat has nothing to be redrawn "for the lesson".
            is DrivenUiCard.ImageCard -> ImageCardView(
                card = card,
                personalizing = card.prompt in personalizingImages,
                onPersonalize = if (lessonCard && card.prompt.isNotBlank()) {
                    { onPersonalizeImage(card) }
                } else {
                    null
                },
            )
            is DrivenUiCard.LessonAnimationCard -> LessonAnimationCardView(card)
            is DrivenUiCard.LessonVideoCard -> LessonVideoCardView(
                card = card,
                analyzing = card.prompt in analyzingVideos,
                onVideoChosen = { uri -> onLessonVideo(card.prompt, uri) },
            )
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
                        contentDescription = tr("Ошибка", "Error"),
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
        CardTitleRow(icon = Icons.AutoMirrored.Filled.PlaylistAddCheck, title = card.title)
        CardDivider()

        if (itemsState.isEmpty()) {
            Text(
                text = tr("Нет задач", "No tasks"),
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
            title = tr("Прогноз для: ${card.city}", "Forecast for: ${card.city}"),
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
                Text(if (isRunning) tr("Пауза", "Pause") else tr("Старт", "Start"))
            }

            TextButton(
                onClick = {
                    isRunning = false
                    timeLeft = card.durationSec
                }
            ) {
                Icon(imageVector = Icons.Default.Refresh, contentDescription = tr("Сброс", "Reset"))
                Spacer(modifier = Modifier.width(4.dp))
                Text(tr("Сброс", "Reset"))
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
            text = tr("Форма ввода данных", "Data entry form"),
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
            Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
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
            // answerIndex < 0 — the right option is not set (e.g. an open poll without a "right" answer): the choice
            // is not highlighted as wrong, see f646e64 for the same class of bug.
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
            text = tr("Закрепим пройденное", "Let's review"),
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
        // The Academy capstone (Task 23): rendered ONLY when the card itself was built by
        // LessonChatController.postCompletion for agent_blueprint — ordinary lessons set no primaryAction,
        // so nothing appears here and the old behavior does not change.
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
 * A live heartbeat card: it subscribes to the Room row by [DrivenUiCard.ReminderCard.heartbeatId] and
 * reflects it (schedule/status/last result) in real time — without re-rendering the whole message. The
 * cancel button is shown only while the reminder is still "scheduled".
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
            text = tr("Напоминание не найдено", "Reminder not found"),
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
                Text(tr("Отменить", "Cancel"))
            }
        }
    }
}

/**
 * A self-contained notification permission request card: it holds its own launcher and reflects the REAL
 * state (areNotificationsEnabled), refreshing it on ON_RESUME — no external action routing is needed.
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

    // Coming back from the system dialog/settings → the state is recomputed so the card does not lie.
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
                Text(tr("Уведомления включены", "Notifications are on"), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        // < 33: there is no runtime permission — lead to the system notification settings.
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
 * An image/diagram in a lesson step. The source is a bundled asset (tried FIRST so it works offline), with
 * a fallback to the network URL if there is no asset or it failed to load. SVG and raster are decoded by
 * the app's shared Coil singleton (SvgDecoder is plugged in in [com.aiopenpocket.AiOpenPocketApp.newImageLoader]).
 * This used to build ITS OWN ImageLoader per card instance — its cache died with the feed item, and when
 * the bubble came back into the viewport the image loaded again (an empty bubble → a jump).
 */
@Composable
fun ImageCardView(
    card: DrivenUiCard.ImageCard,
    personalizing: Boolean = false,
    // null — no button. Not a Boolean flag next to the callback: "show the button with no action" does not exist.
    onPersonalize: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val assetData = card.asset.takeIf { it.isNotBlank() }?.let { "file:///android_asset/$it" }
    val urlData = card.url.takeIf { it.isNotBlank() }
    // The asset first; on a load error we switch to the URL (once).
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
                    text = tr("Нет изображения", "No image"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        } else {
            AsyncImage(
                model = ImageRequest.Builder(context).data(data).crossfade(true).build(),
                contentDescription = card.caption.ifBlank { null },
                // A fixed aspect: the bubble's height is known BEFORE the image loads, so the feed does not "swell"
                // and jump when the image arrives (previously FillWidth without a height: the bubble was
                // born collapsed). Fit — schemes/diagrams are not cropped; letterboxing at the edges is
                // possible.
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(12.dp))
                    // The light backing: transparent SVGs were drawn "for a light background", and after the glass
                    // redesign the bubble became transparent over the dark aurora — without it the diagram vanished.
                    .background(LessonImageBacking),
                onError = {
                    // The asset did not load — try the network URL if it is set and not tried yet.
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
        if (onPersonalize != null) {
            // Muse Image thinks for tens of seconds before drawing: without an explicit "Drawing…" repeated taps
            // would look like a broken button, and each would be one more paid generation.
            OutlinedButton(onClick = onPersonalize, enabled = !personalizing) {
                if (personalizing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Рисую под тебя…", "Drawing it for you…"))
                } else {
                    Icon(Icons.Default.Brush, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Нарисуй под меня", "Draw it for me"))
                }
            }
        }
    }
}

/** "reveal": a question; a button reveals the answer. The step advances with the "Next" button. */
@Composable
fun RevealCardView(card: DrivenUiCard.RevealCard) {
    // rememberSaveable: the reveal survives the item being recycled in LazyColumn and a rotation
    // (a plain remember reset the card to "closed" when scrolled off screen and back).
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
                Text(tr("Показать ответ", "Show answer"))
            }
        }
    }
}

/** "concept": a term, a definition, an example. A static card. */
@Composable
fun ConceptCardView(card: DrivenUiCard.ConceptCard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitleRow(icon = Icons.Default.School, title = card.term)
        CardDivider()
        Text(text = card.definition, style = MaterialTheme.typography.bodyMedium)
        if (card.example.isNotBlank()) {
            Text(
                text = tr("Пример: ${card.example}", "Example: ${card.example}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** "example": a heading and a breakdown into parts (text + a role badge + a note). A static card. */
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
                        text = part.role.uppercase(Locale.ROOT),
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

/** How many misses on a fill step we tolerate before showing the answer and letting the learner move on. */
private const val FILL_MISSES_BEFORE_REVEAL = 2

/**
 * "fill": fill in the gap. A correct input advances the lesson (like a passed quiz).
 *
 * After [FILL_MISSES_BEFORE_REVEAL] misses the card shows the answer and a "Next" button: fill has no "Next"
 * of its own, and a learner who did not guess the exact word used to be stuck in the lesson forever. A
 * peeked answer advances the lesson the same way as a solved one — the point of the step is to read the
 * right word, not an exam.
 */
@Composable
fun FillCardView(card: DrivenUiCard.FillCard, onSolved: () -> Unit) {
    // rememberSaveable: being solved (and the draft) survive recycling/rotation. Otherwise the card was reset to
    // unsolved, and solving it again called onSolved ONE MORE TIME — the lesson cursor jumped an extra step.
    // onSolved is called only on the solved=false→true transition, so a restored solved=true does not
    // advance again. The miss counter is saveable too: otherwise a screen rotation would hide an answer
    // already shown.
    var input by rememberSaveable(card) { mutableStateOf("") }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var revealed by rememberSaveable(card) { mutableStateOf(false) }
    var misses by rememberSaveable(card) { mutableIntStateOf(0) }
    var wrong by remember(card) { mutableStateOf(false) }

    fun check() {
        if (card.accepts(input)) { solved = true; wrong = false; onSolved() } else { wrong = true; misses++ }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = card.template.replace("___", "  _____  "),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (solved) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A peeked answer gets no green checkmark: honest that it was read, not guessed.
                if (revealed) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.outline)
                } else {
                    Icon(Icons.Default.Check, null, tint = CardSuccessGreen)
                }
                Text(card.answer, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; wrong = false },
                singleLine = true,
                isError = wrong,
                placeholder = { Text(tr("Впиши пропущенное слово", "Type the missing word")) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (misses >= FILL_MISSES_BEFORE_REVEAL) {
                Text(
                    tr("Правильно: «${card.answer}»", "Correct answer: “${card.answer}”"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (wrong) {
                Text(tr("Не то — попробуй ещё", "Not quite — try again"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { check() }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(tr("Проверить", "Check"))
            }
            if (misses >= FILL_MISSES_BEFORE_REVEAL) {
                OutlinedButton(
                    onClick = { revealed = true; solved = true; wrong = false; onSolved() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("Дальше", "Next"))
                }
            }
        }
    }
}

/** "sort": arrange the items with up/down arrows. The right order advances the lesson. */
@Composable
fun SortCardView(card: DrivenUiCard.SortCard, onSolved: () -> Unit) {
    // rememberSaveable: the arrangement and being solved survive the item being recycled in LazyColumn and a
    // rotation. remember used to reset the card to unsolved, and solving it again called onSolved ONE MORE
    // TIME — the lesson cursor jumped an extra step. onSolved is called only on the solved=false→true
    // transition, so a restored solved=true does not advance again.
    // The order is stored as indexes: duplicate strings in items do not confuse the restore.
    val order = rememberSaveable(
        card,
        saver = listSaver<SnapshotStateList<Int>, Int>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { card.items.indices.toList().toMutableStateList() }
    // correct must be a full permutation of the items' indexes. If it is broken (out of range, duplicates,
    // the wrong length — a frequent defect of an LLM card or an authoring mistake in a lesson), we degrade
    // to the original order so the step stays solvable with "Check" instead of getting stuck forever.
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
                    }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, tr("Вверх", "Move up")) }
                    IconButton(onClick = {
                        if (i < order.size - 1) { val t = order[i + 1]; order[i + 1] = order[i]; order[i] = t; wrong = false }
                    }, enabled = i < order.size - 1) { Icon(Icons.Default.KeyboardArrowDown, tr("Вниз", "Move down")) }
                }
            }
        }
        if (solved) {
            if (card.explain.isNotBlank()) {
                Text("✓ ${card.explain}", style = MaterialTheme.typography.bodySmall, color = CardSuccessGreen)
            }
        } else {
            if (wrong) {
                Text(tr("Пока не тот порядок — переставь и проверь снова", "Not the right order yet — rearrange and check again"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    // The displayed strings are compared (not the indexes): with duplicates in items any visually identical
                    // order counts — as before.
                    if (order.map { card.items[it] } == target) { solved = true; wrong = false; onSolved() } else wrong = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Проверить", "Check")) }
        }
    }
}

/** "match": match terms with definitions by tapping. A full correct match advances the lesson. */
@Composable
fun MatchCardView(card: DrivenUiCard.MatchCard, onSolved: () -> Unit) {
    val terms = card.pairs.map { it.term }
    // Definitions are shown in a shuffled (stable) order. It used to be a shift by 1 — the right definition was
    // almost always "the next one in the list", and the matching could be guessed.
    // avoidIdentity: the original order would give away all pairs at once.
    val displayDefOrder = remember(card) {
        val seed = card.pairs.fold(0) { acc, p -> (acc * 31 + p.term.hashCode()) * 31 + p.def.hashCode() }
        stableShuffledIndices(card.pairs.size, seed, avoidIdentity = true)
    }
    // rememberSaveable: the matching and being solved survive recycling/rotation (see SortCardView — the same
    // class of bug with a repeated onSolved).
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
    ) { mutableStateMapOf() } // termIndex -> the original index of the pair (which is also the right term)
    var selectedTerm by remember(card) { mutableStateOf<Int?>(null) }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var wrong by remember(card) { mutableStateOf(false) }
    val correctGreen = CardSuccessGreen

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.instruction.isNotBlank()) {
            Text(card.instruction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Terms
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
                    text = assignedDef ?: tr("выбери →", "pick →"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (assignedDef != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
        CardDivider()
        Text(tr("Определения:", "Definitions:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        // Definition buttons: a tap assigns it to the selected term
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
                            // remove this def from its previous term, then assign
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
            Text(tr("✓ Верно!", "✓ Correct!"), style = MaterialTheme.typography.bodySmall, color = correctGreen)
        } else {
            if (wrong) {
                Text(tr("Не всё совпало — поправь пары", "Not all pairs match — fix them"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    if (assign.size == terms.size && assign.all { it.key == it.value }) { solved = true; wrong = false; onSolved() } else wrong = true
                },
                enabled = assign.size == terms.size,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Проверить", "Check")) }
        }
    }
}

/** "scenario": a situation + one question with options. The answer advances the lesson. */
@Composable
fun ScenarioCardView(card: DrivenUiCard.ScenarioCard, onSolved: () -> Unit) {
    // rememberSaveable: the answer survives recycling/rotation — a repeated tap on a "reset" card used to call
    // onSolved ONE MORE TIME and push the lesson an extra step. -1 = not answered yet (the autosaver does
    // not save Int?). The ORIGINAL option index is stored — checking against answerIndex and restoring do
    // not depend on the display order.
    var selected by rememberSaveable(card) { mutableStateOf(-1) }
    // The options are in a shuffled (stable) order: in the authored JSON the right one is always first.
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
            // answerIndex < 0 — the right option is unknown (not set/broken): nothing is highlighted as "right/wrong",
            // so as not to mislead with a false key.
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
                        // Answered — move on regardless of correctness: the right option + the explanation are visible.
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
