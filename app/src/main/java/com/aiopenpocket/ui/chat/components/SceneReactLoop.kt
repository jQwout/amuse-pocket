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
import com.aiopenpocket.i18n.tr
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** One beat of the lesson's example: the loop phase (0 think, 1 act, 2 observe, 3 answer), the text and the round number. */
private data class ReactBeat(val phase: Int, val text: String, val round: Int)

// Display-time functions, not top-level vals: tr() has to run when the scene is shown, in the current language.
private fun phases() = listOf(tr("Думаю", "Thinking"), tr("Действую", "Acting"), tr("Наблюдаю", "Observing"))
private const val ANSWER = 3

// The same example as in the lesson's text: a reminder an hour before the meeting with Max. There used to be
// "notes" and "weather" here — the app has no such tools, and the learner could not repeat the example in the
// chat. Any chat has the calendar and reminders, so the example reproduces as is.
private fun beats() = listOf(
    ReactBeat(0, tr("Не знаю, когда встреча, — гляну в календарь", "I don't know when the meeting is, so I'll check the calendar"), 1),
    ReactBeat(1, tr("Инструмент «Календарь»: показать события", "Calendar tool: show events"), 1),
    ReactBeat(2, tr("Встреча с Максом — завтра в 15:00", "Meeting with Max: tomorrow at 3:00 PM"), 1),
    ReactBeat(0, tr("Значит, напомнить надо завтра в 14:00", "So the reminder should be tomorrow at 2:00 PM"), 2),
    ReactBeat(1, tr("Инструмент «Напоминания»: на 14:00", "Reminders tool: for 2:00 PM"), 2),
    ReactBeat(2, tr("Напоминание запланировано", "Reminder scheduled"), 2),
    ReactBeat(0, tr("Данных хватает — можно отвечать", "I have enough data, so I can answer"), 3),
    ReactBeat(ANSWER, tr("Напомню завтра в 14:00 — за час до встречи с Максом", "I'll remind you tomorrow at 2:00 PM, an hour before the meeting with Max"), 3),
)

// The phases in a circle: "Thinking" on top, "Acting" bottom right, "Observing" bottom left.
private val ANGLES = listOf(-90f, 30f, 150f)

/**
 * "The ReAct loop": the user leads the agent around the circle with a button. A dot runs from phase to phase,
 * the current phase is highlighted, and under the circle is what the agent is doing right now. Three rounds —
 * and an answer.
 */
@Composable
internal fun ReactLoopScene() {
    var step by rememberSaveable { mutableIntStateOf(-1) }
    val beats = beats()
    val phases = phases()
    val beat = beats.getOrNull(step)
    val previousPhase = beats.getOrNull(step - 1)?.phase?.takeIf { it < ANSWER }
    val travel = remember { Animatable(1f) }
    LaunchedEffect(step) {
        travel.snapTo(0f)
        travel.animateTo(1f, tween(450))
    }
    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Задача: «напомни за час до встречи с Максом»", "Task: “remind me an hour before my meeting with Max”"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Canvas(modifier = Modifier.fillMaxWidth().height(210.dp)) {
            val center = Offset(size.width / 2, size.height / 2 + 8.dp.toPx())
            val radius = min(size.width, size.height) / 2 - 30.dp.toPx()
            fun at(angle: Float) = Offset(
                center.x + radius * cos(Math.toRadians(angle.toDouble())).toFloat(),
                center.y + radius * sin(Math.toRadians(angle.toDouble())).toFloat(),
            )
            drawCircle(primary.copy(alpha = 0.22f), radius, center, style = Stroke(width = 3.dp.toPx()))

            // The dot runs along an arc from the previous phase to the current one — it shows this is a circle, not a list.
            if (beat != null && beat.phase < ANSWER && previousPhase != null) {
                val from = ANGLES[previousPhase]
                var to = ANGLES[beat.phase]
                if (to <= from) to += 360f
                drawCircle(primary, 7.dp.toPx(), at(from + (to - from) * travel.value))
            }

            phases.forEachIndexed { index, label ->
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
                // "round" is the lesson's word for one turn of the loop (the limits are measured in it too).
                if (beat == null) tr("старт", "start") else tr("виток ${beat.round}", "round ${beat.round}"),
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
                    beat == null -> tr("Нажми «Запустить» и веди агента по кругу сам", "Tap “Start” and lead the agent around the loop yourself")
                    beat.phase == ANSWER -> tr("Ответ: ${beat.text}", "Answer: ${beat.text}")
                    else -> "${phases[beat.phase]}: ${beat.text}"
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (beat?.phase == ANSWER) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
            )
        }
        Button(onClick = { step = if (step >= beats.lastIndex) -1 else step + 1 }, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    step < 0 -> tr("Запустить", "Start")
                    step >= beats.lastIndex -> tr("Сначала", "Start over")
                    else -> tr("Следующий шаг", "Next step")
                },
            )
        }
    }
}
