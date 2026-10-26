package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.roundToInt

/** A day flies by in 7 seconds — enough to follow, not enough to get bored. */
private const val DAY_MS = 7000
/** The only reason to speak up in the day: at 14:00 a deadline for tomorrow appears in the tasks. */
private const val DEADLINE_AT = 14

/** A cron task: the hour, the label on the scale and the push text. The same examples as in the lesson: a report at 9, "call mom" at 18. */
private class CronJob(val hour: Int, val tag: String, val push: String)
private val CRON = listOf(CronJob(9, "отчёт", "«Время отчёта»"), CronJob(18, "маме", "«Позвони маме»"))

private class DayEvent(val hour: Int, val order: Int, val text: String)

private fun wakes(every: Int) = (every until 24 step every).toList()
private fun hhmm(hour: Int) = String.format(Locale.US, "%02d:00", hour)

/**
 * "Heartbeat and cron": the user runs a day in fast forward. Cron fires strictly on the clock, heartbeat wakes
 * the agent every N hours (a slider), and it is almost always silent — HEARTBEAT_OK. It speaks up once, when
 * there is a reason. The counters show: many wake-ups, few pushes.
 */
@Composable
internal fun DayClockScene() {
    var every by rememberSaveable { mutableIntStateOf(3) }
    var runs by remember { mutableIntStateOf(0) }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(runs) {
        if (runs == 0) return@LaunchedEffect
        clock.snapTo(0f)
        clock.animateTo(24f, tween(DAY_MS, easing = LinearEasing))
    }
    val time = clock.value
    val running = clock.isRunning
    val wakeHours = wakes(every)
    val speakAt = wakeHours.first { it >= DEADLINE_AT }
    val woke = wakeHours.count { it <= time }
    val heartbeatPushes = if (speakAt <= time) 1 else 0
    val cronPushes = CRON.count { it.hour <= time }
    val events = buildList {
        add(DayEvent(DEADLINE_AT, 0, "${hhmm(DEADLINE_AT)} в задачах: дедлайн на завтра"))
        CRON.forEach { add(DayEvent(it.hour, 1, "${hhmm(it.hour)} cron: пуш ${it.push}")) }
        wakeHours.forEach { h ->
            add(DayEvent(h, 2, if (h == speakAt) "${hhmm(h)} heartbeat: завтра дедлайн — пишу!" else "${hhmm(h)} heartbeat: HEARTBEAT_OK — молчу"))
        }
    }.filter { it.hour <= time }.sortedWith(compareBy<DayEvent>({ it.hour }, { it.order })).takeLast(3)
    val finished = time >= 24f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Сутки агента: cron по часам, heartbeat — по пробуждениям", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        DayTrack(time = time, wakeHours = wakeHours, speakAt = speakAt, modifier = Modifier.fillMaxWidth().height(100.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("■ cron: по часам", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("● heartbeat: решает сам", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp),
        ) {
            Text("Журнал агента · ${hhmm(time.toInt().coerceAtMost(24))}", style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f))
            // Three lines are always reserved: the log grows, the card does not.
            (0 until 3).forEach { row ->
                val event = events.getOrNull(row)
                Text(
                    text = event?.text ?: " ",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (event != null && event.hour == speakAt && event.order == 2) FontWeight.Bold else FontWeight.Normal,
                    color = SceneInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Stat(value = woke, label = "пробуждений = вызовов LLM", modifier = Modifier.weight(1f))
            Stat(value = heartbeatPushes, label = "пушей от heartbeat", modifier = Modifier.weight(1f))
            Stat(value = cronPushes, label = "пушей от cron", modifier = Modifier.weight(1f))
        }
        Text("Heartbeat будит каждые $every ч", style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Slider(
            value = every.toFloat(),
            onValueChange = { every = it.roundToInt() },
            valueRange = 1f..6f,
            steps = 4,
            enabled = !running,
        )
        Button(onClick = { runs++ }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    running -> "Идут сутки…"
                    runs == 0 -> "Запустить сутки"
                    else -> "Ещё раз"
                },
            )
        }
        Text(
            text = if (finished) {
                "Проснулся $woke раз, написал один — в ${hhmm(speakAt)}. Cron — по часам, heartbeat — проснуться и решить, вплоть до «промолчать»"
            } else {
                "Cron сработает в 9:00 и 18:00 при любом раскладе. Heartbeat проснётся и сам решит, есть ли повод писать"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (finished) FontWeight.SemiBold else FontWeight.Normal,
            minLines = 3,
        )
    }
}

/** The day's scale: cron tasks on top, the night and the running "now" on the line, heartbeat wake-ups below. */
@Composable
private fun DayTrack(time: Float, wakeHours: List<Int>, speakAt: Int, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    Canvas(modifier = modifier) {
        val left = 10.dp.toPx()
        val right = size.width - 10.dp.toPx()
        fun x(h: Float) = left + (right - left) * h / 24f
        val cronY = 26.dp.toPx()
        val lineY = 50.dp.toPx()
        val beatY = 72.dp.toPx()
        val band = 8.dp.toPx()

        // The night is before 7:00 and after 22:00: it shows that heartbeat wakes the agent then too, but the person does not hear a sound.
        val night = Color(0xFF8E9AC4).copy(alpha = 0.35f)
        drawRect(night, topLeft = Offset(x(0f), lineY - band), size = Size(x(7f) - x(0f), band * 2))
        drawRect(night, topLeft = Offset(x(22f), lineY - band), size = Size(x(24f) - x(22f), band * 2))
        drawLine(SceneInk.copy(alpha = 0.35f), Offset(left, lineY), Offset(right, lineY), strokeWidth = 2.dp.toPx())
        listOf(0, 6, 12, 18, 24).forEach { h ->
            val label = measurer.measure("$h", TextStyle(fontSize = 10.sp, color = SceneInk.copy(alpha = 0.55f)))
            drawText(label, topLeft = Offset(x(h.toFloat()) - label.size.width / 2, beatY + 12.dp.toPx()))
        }

        // The deadline appears at 14:00 — a dashed line from the top down to the line.
        if (time >= DEADLINE_AT) {
            drawLine(
                color = error,
                start = Offset(x(DEADLINE_AT.toFloat()), 16.dp.toPx()),
                end = Offset(x(DEADLINE_AT.toFloat()), lineY),
                strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
            val tag = measurer.measure("дедлайн", TextStyle(fontSize = 10.sp, color = error, fontWeight = FontWeight.SemiBold))
            drawText(tag, topLeft = Offset(x(DEADLINE_AT.toFloat()) - tag.size.width / 2, 0f))
        }

        val square = 11.dp.toPx()
        CRON.forEach { job ->
            val c = Offset(x(job.hour.toFloat()), cronY)
            val topLeft = Offset(c.x - square / 2, c.y - square / 2)
            drawLine(primary.copy(alpha = 0.3f), Offset(c.x, c.y + square / 2), Offset(c.x, lineY), strokeWidth = 1.dp.toPx())
            drawRoundRect(if (time >= job.hour) primary else Color.White, topLeft, Size(square, square), CornerRadius(2.dp.toPx()))
            drawRoundRect(primary, topLeft, Size(square, square), CornerRadius(2.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            val tag = measurer.measure(job.tag, TextStyle(fontSize = 10.sp, color = SceneInk, fontWeight = FontWeight.SemiBold))
            drawText(tag, topLeft = Offset(c.x - tag.size.width / 2, 0f))
        }

        // Wake-ups: an empty circle — still ahead, gray — woke up and stayed silent, large — spoke up.
        val r = 5.dp.toPx()
        wakeHours.forEach { h ->
            val c = Offset(x(h.toFloat()), beatY)
            when {
                time < h -> drawCircle(primary.copy(alpha = 0.4f), r, c, style = Stroke(1.5.dp.toPx()))
                h == speakAt -> {
                    drawCircle(primary.copy(alpha = 0.25f), r * 2f, c)
                    drawCircle(primary, r * 1.3f, c)
                }
                else -> drawCircle(SceneInk.copy(alpha = 0.35f), r, c)
            }
        }

        if (time > 0f) {
            drawLine(SceneInk, Offset(x(time), cronY - 6.dp.toPx()), Offset(x(time), beatY + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun Stat(value: Int, label: String, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        Text("$value", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SceneInk)
        Text(label, style = MaterialTheme.typography.labelSmall, color = SceneInk.copy(alpha = 0.7f), minLines = 2, maxLines = 2, textAlign = TextAlign.Center)
    }
}
