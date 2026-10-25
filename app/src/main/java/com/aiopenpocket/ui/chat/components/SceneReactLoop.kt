package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Один такт примера из урока: фаза цикла (0 думаю, 1 действую, 2 наблюдаю, 3 ответ), текст и номер круга. */
private data class ReactBeat(val phase: Int, val text: String, val round: Int)

private val PHASES = listOf("Думаю", "Действую", "Наблюдаю")
private const val ANSWER = 3

// Тот же пример, что в тексте урока: напомнить за час до встречи с Максом. Раньше тут были «заметки» и
// «погода» — таких инструментов в приложении нет, и ученик не мог повторить пример в чате. Календарь и
// напоминания есть у любого чата, так что пример воспроизводится как есть.
private val BEATS = listOf(
    ReactBeat(0, "Не знаю, когда встреча, — гляну в календарь", 1),
    ReactBeat(1, "Инструмент «Календарь»: показать события", 1),
    ReactBeat(2, "Встреча с Максом — завтра в 15:00", 1),
    ReactBeat(0, "Значит, напомнить надо завтра в 14:00", 2),
    ReactBeat(1, "Инструмент «Напоминания»: на 14:00", 2),
    ReactBeat(2, "Напоминание запланировано", 2),
    ReactBeat(0, "Данных хватает — можно отвечать", 3),
    ReactBeat(ANSWER, "Напомню завтра в 14:00 — за час до встречи с Максом", 3),
)

// Фазы по кругу: «Думаю» сверху, «Действую» справа снизу, «Наблюдаю» слева снизу.
private val ANGLES = listOf(-90f, 30f, 150f)

/**
 * «Цикл ReAct»: пользователь сам ведёт агента по кругу кнопкой. Точка бежит от фазы к фазе, текущая
 * фаза подсвечена, под кругом — что агент делает прямо сейчас. Три витка — и ответ.
 */
@Composable
internal fun ReactLoopScene() {
    var step by rememberSaveable { mutableIntStateOf(-1) }
    val beat = BEATS.getOrNull(step)
    val previousPhase = BEATS.getOrNull(step - 1)?.phase?.takeIf { it < ANSWER }
    val travel = remember { Animatable(1f) }
    LaunchedEffect(step) {
        travel.snapTo(0f)
        travel.animateTo(1f, tween(450))
    }
    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Задача: «напомни за час до встречи с Максом»", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Canvas(modifier = Modifier.fillMaxWidth().height(210.dp)) {
            val center = Offset(size.width / 2, size.height / 2 + 8.dp.toPx())
            val radius = min(size.width, size.height) / 2 - 30.dp.toPx()
            fun at(angle: Float) = Offset(
                center.x + radius * cos(Math.toRadians(angle.toDouble())).toFloat(),
                center.y + radius * sin(Math.toRadians(angle.toDouble())).toFloat(),
            )
            drawCircle(primary.copy(alpha = 0.22f), radius, center, style = Stroke(width = 3.dp.toPx()))

            // Точка бежит по дуге от прошлой фазы к текущей — видно, что это круг, а не список.
            if (beat != null && beat.phase < ANSWER && previousPhase != null) {
                val from = ANGLES[previousPhase]
                var to = ANGLES[beat.phase]
                if (to <= from) to += 360f
                drawCircle(primary, 7.dp.toPx(), at(from + (to - from) * travel.value))
            }

            PHASES.forEachIndexed { index, label ->
                val active = beat?.phase == index
                val text = measurer.measure(
                    label,
                    TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (active) onPrimary else SceneInk),
                )
                val pad = 10.dp.toPx()
                val pill = Size(text.size.width + pad * 2, text.size.height + pad)
                val p = at(ANGLES[index])
                val topLeft = Offset(p.x - pill.width / 2, p.y - pill.height / 2)
                drawRoundRect(
                    color = if (active) primary else Color.White,
                    topLeft = topLeft,
                    size = pill,
                    cornerRadius = CornerRadius(pill.height / 2),
                )
                drawRoundRect(
                    color = primary.copy(alpha = if (active) 1f else 0.4f),
                    topLeft = topLeft,
                    size = pill,
                    cornerRadius = CornerRadius(pill.height / 2),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                drawText(text, topLeft = Offset(topLeft.x + pad, topLeft.y + pad / 2))
            }

            val roundText = measurer.measure(
                // «виток» — слово урока для одного круга цикла (им же меряют лимиты).
                if (beat == null) "старт" else "виток ${beat.round}",
                TextStyle(fontSize = 13.sp, color = SceneInk.copy(alpha = 0.6f)),
            )
            drawText(roundText, topLeft = Offset(center.x - roundText.size.width / 2, center.y - roundText.size.height / 2))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (beat?.phase == ANSWER) primary.copy(alpha = 0.14f) else Color.White)
                .padding(12.dp),
        ) {
            Text(
                text = when {
                    beat == null -> "Нажми «Запустить» и веди агента по кругу сам"
                    beat.phase == ANSWER -> "Ответ: ${beat.text}"
                    else -> "${PHASES[beat.phase]}: ${beat.text}"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (beat?.phase == ANSWER) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
            )
        }
        Button(onClick = { step = if (step >= BEATS.lastIndex) -1 else step + 1 }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    step < 0 -> "Запустить"
                    step >= BEATS.lastIndex -> "Сначала"
                    else -> "Следующий шаг"
                },
            )
        }
    }
}
