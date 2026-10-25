package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Один прогон: время и токены (пример, не замер), ответ, вердикт и короткая оценка для таблицы. */
private class ThinkRun(val seconds: Int, val tokens: Int, val answer: String, val verdict: String, val mark: String, val good: Boolean)

/** Вопрос и насколько длинные у него размышления (доля ширины полосы): простому думать почти не о чем. */
private class ThinkAsk(val chip: String, val text: String, val depth: Float, val runs: List<ThinkRun>)

private val ASKS = listOf(
    ThinkAsk(
        "Столица Франции?", "Столица Франции?", 0.35f,
        listOf(
            ThinkRun(1, 20, "Париж", "Верно и сразу — для простого вопроса Instant и есть умный выбор", "верно", true),
            ThinkRun(5, 400, "Париж", "Тот же «Париж» — только токенов в 20 раз больше", "верно", true),
            ThinkRun(20, 1500, "Париж", "Снова «Париж» — 1500 токенов вместо 20. Думать было не о чем", "верно", true),
        ),
    ),
    ThinkAsk(
        "План тренировок", "Составь план тренировок на неделю с учётом больного колена", 1f,
        listOf(
            ThinkRun(2, 150, "Пн — бег, Ср — приседания, Пт — прыжки", "Поверхностно: колено не учтено. Тут стоит дать модели подумать", "мелко", false),
            ThinkRun(25, 3000, "7 дней: плавание, велотренажёр, упражнения без ударной нагрузки, дни отдыха", "Колено учтено — но дольше и дороже", "хорошо", true),
            ThinkRun(60, 9000, "Сравнил три плана и взял лучший: щадящая нагрузка, замены упражнений, отдых", "Самый продуманный план — и самый дорогой", "отлично", true),
        ),
    ),
)

private val MODES = listOf(
    "Instant" to "отвечает сразу, без долгих размышлений",
    "Thinking" to "сначала рассуждает шаг за шагом",
    "Contemplating" to "несколько рассуждений параллельно",
)

/** Длины трёх параллельных рассуждений Contemplating относительно одного — разные, как у разных агентов. */
private val LANES = listOf(0.8f, 1f, 0.65f)

/**
 * «Режимы Muse Spark»: пользователь выбирает вопрос и режим и жмёт «Спросить». Instant отвечает сразу,
 * Thinking сначала растит длинную полосу размышлений, Contemplating — три параллельных, которые сходятся
 * в один ответ. Таблица копит время и токены: на простом вопросе думать незачем, на сложном — окупается.
 */
@Composable
internal fun ThinkingModesScene() {
    var askIndex by rememberSaveable { mutableIntStateOf(0) }
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var ran by rememberSaveable { mutableIntStateOf(0) } // бит askIndex*3+mode — этот прогон уже был
    var shown by rememberSaveable { mutableIntStateOf(-1) } // какой прогон сейчас на полосах, -1 — никакой
    var finished by rememberSaveable { mutableIntStateOf(-1) } // какой прогон уже доиграл до ответа
    var runId by remember { mutableIntStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(runId) {
        if (runId == 0 || shown < 0) return@LaunchedEffect
        val depth = ASKS[shown / 3].depth
        val duration = when (shown % 3) { 0 -> 400; 1 -> (2000 * depth).toInt(); else -> (2600 * depth).toInt() }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(duration, easing = LinearEasing))
        finished = shown
        ran = ran or (1 shl shown)
    }
    val running = progress.isRunning
    val ask = ASKS[askIndex]
    val done = shown >= 0 && finished == shown
    // Пока анимация не стартовала, полосы пустые: иначе на кадр мелькнул бы прошлый прогон.
    val p = when {
        shown < 0 -> 0f
        done -> 1f
        running -> progress.value
        else -> 0f
    }
    val run = if (shown >= 0) ASKS[shown / 3].runs[shown % 3] else null
    val primary = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Режимы — переключатель приложения Meta AI; через API, как в этом приложении, Muse Spark думает всегда.
        Text("Режимы Muse Spark в Meta AI: выбери, сколько думать", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ASKS.forEachIndexed { index, item ->
                FilterChip(selected = askIndex == index, enabled = !running, onClick = { askIndex = index; shown = -1 }, label = { Text(item.chip) })
            }
        }
        Text("Вопрос: «${ask.text}»", style = MaterialTheme.typography.bodyMedium, minLines = 2)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MODES.forEachIndexed { index, (name, _) ->
                FilterChip(selected = mode == index, enabled = !running, onClick = { mode = index; shown = -1 }, label = { Text(name) })
            }
        }
        Text(MODES[mode].second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        Button(
            onClick = { shown = askIndex * 3 + mode; finished = -1; runId++ },
            enabled = !running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Спросить") }
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val start = Offset(14.dp.toPx(), size.height * 0.58f)
            val finish = Offset(size.width - 14.dp.toPx(), size.height * 0.58f)
            val barX = start.x + 18.dp.toPx()
            val maxLen = finish.x - barX - 40.dp.toPx()
            val activeMode = if (shown >= 0) shown % 3 else mode
            val depth = if (shown >= 0) ASKS[shown / 3].depth else ask.depth
            val grow = (p / 0.8f).coerceAtMost(1f)
            val merge = ((p - 0.8f) / 0.2f).coerceIn(0f, 1f)
            val title = when (activeMode) { 0 -> "без размышлений — сразу к ответу"; 1 -> "размышления"; else -> "три рассуждения параллельно" }
            drawText(measurer.measure(title, TextStyle(fontSize = 11.sp, color = SceneInk.copy(alpha = 0.6f))), topLeft = Offset(start.x, 0f))
            if (activeMode == 0) {
                drawLine(primary, start, lerp(start, finish, p), strokeWidth = 3.dp.toPx())
            } else {
                val ys = if (activeMode == 1) listOf(size.height * 0.58f) else listOf(0.3f, 0.58f, 0.86f).map { size.height * it }
                val factors = if (activeMode == 1) listOf(1f) else LANES
                ys.forEachIndexed { i, y ->
                    val length = maxLen * depth * factors[i] * grow
                    val color = if (activeMode == 1) ScenePalette[4] else ScenePalette[listOf(0, 3, 5)[i]]
                    val barTop = Offset(barX, y - 6.dp.toPx())
                    drawLine(SceneInk.copy(alpha = 0.3f), start, Offset(barX, y), strokeWidth = 1.5.dp.toPx())
                    drawRoundRect(color, barTop, Size(length.coerceAtLeast(1f), 12.dp.toPx()), CornerRadius(6.dp.toPx()))
                    drawRoundRect(SceneInk.copy(alpha = 0.25f), barTop, Size(length.coerceAtLeast(1f), 12.dp.toPx()), CornerRadius(6.dp.toPx()), style = Stroke(1.dp.toPx()))
                    // Когда размышления кончились, каждая полоса тянется к одной точке — ответу.
                    if (merge > 0f) {
                        val from = Offset(barX + maxLen * depth * factors[i], y)
                        drawLine(primary.copy(alpha = 0.7f), from, lerp(from, finish, merge), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            drawCircle(SceneInk.copy(alpha = 0.5f), 6.dp.toPx(), start, style = Stroke(2.dp.toPx()))
            if (p >= 1f) drawCircle(primary, 8.dp.toPx(), finish)
        }
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = when {
                    run == null -> "Выбери режим и нажми «Спросить»"
                    !done -> listOf("Отвечает…", "Размышляет…", "Три рассуждения идут параллельно…")[shown % 3]
                    else -> "Ответ: ${run.answer}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = SceneInk,
                minLines = 2,
            )
            Text(
                text = if (done && run != null) run.verdict else " ",
                style = MaterialTheme.typography.bodySmall,
                color = if (run?.good == false) MaterialTheme.colorScheme.error else primary,
                minLines = 2,
            )
        }
        Text("Время и токены — пример, не замер", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        MODES.forEachIndexed { index, (name, _) ->
            val key = askIndex * 3 + index
            val live = shown == key
            val r = ask.runs[index]
            val share = when {
                live -> p
                ran and (1 shl key) != 0 -> 1f
                else -> 0f
            }
            ResultRow(
                name = name,
                time = if (share > 0f) "≈ ${(r.seconds * share).toInt().coerceAtLeast(1)} с" else "—",
                tokens = if (share > 0f) "≈ ${(r.tokens * share).toInt()} ток." else "—",
                mark = if (share >= 1f) r.mark else "",
                good = r.good,
                active = live,
            )
        }
        AnimatedVisibility(visible = Integer.bitCount(ran) >= 2, enter = fadeIn()) {
            Text(
                "Размышления стоят токенов и времени — режим выбирают под задачу",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = primary,
            )
        }
    }
}

@Composable
private fun ResultRow(name: String, time: String, tokens: String, mark: String, good: Boolean, active: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) ScenePalette[0] else Color.White)
            .padding(horizontal = 10.dp),
    ) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = SceneInk, maxLines = 1, modifier = Modifier.width(104.dp))
        Text(time, style = MaterialTheme.typography.bodySmall, color = SceneInk, modifier = Modifier.width(52.dp))
        Text(tokens, style = MaterialTheme.typography.bodySmall, color = SceneInk, modifier = Modifier.width(80.dp))
        Text(
            mark,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (good) primary else MaterialTheme.colorScheme.error,
            maxLines = 1,
        )
    }
}
