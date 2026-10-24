package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.chat.DrivenUiCard
import java.util.Locale
import kotlin.math.exp
import kotlin.random.Random

/**
 * Шаг урока "animation": процедурная сцена вместо видеофайла. Код на Compose весит килобайты, а не
 * мегабайты, работает без сети и может быть интерактивным — урок становится тем, что можно
 * потрогать, а не только посмотреть.
 */
@Composable
internal fun LessonAnimationCardView(card: DrivenUiCard.LessonAnimationCard) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        when (card.scene) {
            "tokens" -> TokensScene()
            "temperature" -> TemperatureScene()
            "video_frames" -> VideoFramesScene()
            "react_loop" -> ReactLoopScene()
            "context_window" -> ContextWindowScene()
            "rag" -> RagScene()
            "persona_switch" -> PersonaSwitchScene()
            "chat_vs_agent" -> ChatVsAgentScene()
            "prompt_layers" -> PromptLayersScene()
            "prompt_sharpen" -> PromptSharpenScene()
            "confident_wrong" -> ConfidentWrongScene()
            "tool_call" -> ToolCallScene()
            "skill_levels" -> SkillLevelsScene()
            "data_flow" -> DataFlowScene()
            "risk_gate" -> RiskGateScene()
            "hook_timeline" -> HookTimelineScene()
            "workflow_vs_agent" -> WorkflowVsAgentScene()
            "chain_router" -> ChainRouterScene()
            "context_budget" -> ContextBudgetScene()
            "plan_gate" -> PlanGateScene()
            "goal_loop" -> GoalLoopScene()
            "draft_critique" -> DraftCritiqueScene()
            "step_evidence" -> StepEvidenceScene()
            "day_clock" -> DayClockScene()
            "arena_swarm" -> ArenaSwarmScene()
            "agent_assembler" -> AgentAssemblerScene()
            "blueprint" -> BlueprintScene()
            "meta_lineup" -> MetaLineupScene()
            "open_weights" -> OpenWeightsScene()
            "thinking_modes" -> ThinkingModesScene()
            "image_reasoning" -> ImageReasoningScene()
            // Сюда попадает только урок новее приложения: проверка уроков не пустит неизвестную сцену в сборку.
            else -> Text("Эта анимация появится в следующей версии приложения.", style = MaterialTheme.typography.bodyMedium)
        }
        if (card.caption.isNotBlank()) {
            Text(
                text = card.caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// --- «Токены»: одна мысль по-русски и по-английски рассыпается на кусочки ---

/** Кусочки — пример, а не вывод настоящего токенизатора: у каждой модели свой словарь. Ведущий пробел — часть токена. */
private val RU_TOKENS = listOf("Пос", "овет", "уй", ",", " что", " пригот", "овить", " на", " у", "жин")
private val EN_TOKENS = listOf("Suggest", " what", " to", " cook", " for", " dinner")

/** Светлые тона, читаемые тёмным текстом на серебре карточки урока в любой теме. */
internal val ScenePalette = listOf(
    Color(0xFFB9D7F0), Color(0xFFF4C7A1), Color(0xFFC9E4B4),
    Color(0xFFE6C3E8), Color(0xFFF6E3A1), Color(0xFFB8E3DD),
)
internal val SceneInk = Color(0xFF1F1D24)

private const val TOKEN_STEP_MS = 320

@Composable
private fun TokensScene() {
    // Сколько кусочков уже «откололось». Сначала русская строка, затем английская — одной шкалой.
    var run by remember { mutableIntStateOf(0) }
    val split = remember { Animatable(0f) }
    val total = RU_TOKENS.size + EN_TOKENS.size
    LaunchedEffect(run) {
        split.snapTo(0f)
        kotlinx.coroutines.delay(600) // секунду показываем фразы целиком — иначе не видно, что их режут
        split.animateTo(total.toFloat(), tween(durationMillis = total * TOKEN_STEP_MS, easing = LinearEasing))
    }
    val ruSplit = split.value.toInt().coerceAtMost(RU_TOKENS.size)
    val enSplit = (split.value.toInt() - RU_TOKENS.size).coerceIn(0, EN_TOKENS.size)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TokenRow(label = "RU", tokens = RU_TOKENS, splitCount = ruSplit)
        TokenRow(label = "EN", tokens = EN_TOKENS, splitCount = enSplit)
        val done = split.value >= total
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (done) "${RU_TOKENS.size} токенов против ${EN_TOKENS.size} — а мысль одна" else " ",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { run++ }, enabled = done) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Ещё раз")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenRow(label: String, tokens: List<String>, splitCount: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "токенов: $splitCount",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        // Пока кусочек не «откололся», он слит с соседями: без зазора и без своего цвета — читается
        // как обычная фраза. Отколотый получает цвет и зазор, и фраза на глазах распадается на токены.
        FlowRow(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            tokens.forEachIndexed { index, token ->
                TokenChip(token = token, split = index < splitCount, color = ScenePalette[index % ScenePalette.size])
            }
        }
    }
}

@Composable
private fun TokenChip(token: String, split: Boolean, color: Color) {
    val background by animateColorAsState(if (split) color else Color.White, tween(220), label = "tokenBg")
    val gap by animateDpAsState(if (split) 3.dp else 0.dp, tween(220), label = "tokenGap")
    val radius = if (split) 8.dp else 2.dp
    Row(
        modifier = Modifier
            .padding(horizontal = gap)
            .clip(RoundedCornerShape(radius))
            .background(background)
            .border(1.dp, if (split) SceneInk.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(radius))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Ведущий пробел — тоже часть токена: показываем его точкой, иначе «пробелы идут в счёт» не увидеть.
        if (token.startsWith(" ")) {
            Text("·", color = SceneInk.copy(alpha = 0.45f), style = MaterialTheme.typography.bodyLarge)
        }
        Text(token.trimStart(), color = SceneInk, style = MaterialTheme.typography.bodyLarge)
    }
}

// --- «Температура»: столбики вероятностей следующего слова и ползунок ---

/** Варианты продолжения и их «сырые» оценки. Числа иллюстративные: у настоящей модели вариантов тысячи. */
private val NEXT_WORDS = listOf(
    "бодрость" to 2.2f,
    "привычка" to 1.6f,
    "ритуал" to 1.3f,
    "удовольствие" to 0.9f,
    "необходимость" to 0.6f,
    "искусство" to -0.2f,
)

private fun probabilities(temperature: Float): List<Float> {
    val scaled = NEXT_WORDS.map { exp((it.second / temperature).toDouble()) }
    val sum = scaled.sum()
    return scaled.map { (it / sum).toFloat() }
}

@Composable
private fun TemperatureScene() {
    var temperature by rememberSaveable { mutableStateOf(1.0f) }
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    val history = remember { mutableStateListOf<String>() }
    val probs = probabilities(temperature)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Кофе утром — это ${picked?.let { NEXT_WORDS[it].first } ?: "…"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        NEXT_WORDS.forEachIndexed { index, (word, _) ->
            ProbabilityBar(word = word, probability = probs[index], highlighted = picked == index)
        }
        Spacer(Modifier.height(4.dp))
        // Две строки по одной, а не одна склеенная: склейка переносилась на узком экране только при
        // некоторых значениях, и ползунок прыгал вниз прямо под пальцем.
        Text(
            text = "Температура: ${String.format(Locale.US, "%.1f", temperature)}",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        Text(
            text = when {
                temperature < 0.5f -> "почти всегда самое вероятное"
                temperature > 1.4f -> "смелые, неожиданные слова"
                else -> "обычный разброс"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        Slider(
            value = temperature,
            onValueChange = { temperature = it },
            valueRange = 0.1f..2.0f,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = {
                val index = sample(probs)
                picked = index
                history.add(0, NEXT_WORDS[index].first)
                if (history.size > 6) history.removeAt(history.lastIndex)
            }) {
                Icon(Icons.Default.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Вытянуть слово")
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (history.isEmpty()) "Тяни несколько раз — и сдвинь ползунок" else history.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ProbabilityBar(word: String, probability: Float, highlighted: Boolean) {
    // Плавно, а не скачком: под пальцем на ползунке распределение «течёт» — в этом весь смысл сцены.
    val shown by animateFloatAsState(probability, tween(250), label = "prob")
    val barColor by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        tween(250),
        label = "barColor",
    )
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = word,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(118.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(shown.coerceIn(0.01f, 1f))
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(barColor),
            )
        }
        Text(
            text = "${(shown * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

private fun sample(probs: List<Float>): Int {
    var r = Random.nextFloat()
    probs.forEachIndexed { index, p ->
        r -= p
        if (r <= 0f) return index
    }
    return probs.lastIndex
}
