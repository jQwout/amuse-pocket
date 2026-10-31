package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiopenpocket.i18n.tr
import java.util.Locale

/** The time on a chip: how a person writes it and how the model lays it out into the call's parameters. */
private class AlarmTime(val label: String, val hour: Int, val minute: Int)

private val TIMES = listOf(AlarmTime("6:30", 6, 30), AlarmTime("7:00", 7, 0), AlarmTime("8:15", 8, 15))

// Three boxes from top to bottom — that is the direction: model → app → model.
// Functions, not top-level vals: the language is read when the scene is drawn, not once at class load.
private fun headers() = listOf(
    tr("Модель → приложению: вызов", "Model → app: the call"),
    tr("Приложение → модели: результат", "App → model: the result"),
    tr("Модель → тебе: ответ", "Model → you: the answer"),
)

private fun notes() = listOf(
    tr("Выбери время и жми «Отправить» — смотри, кто что делает.", "Pick a time and tap “Send” to see who does what."),
    tr(
        "Сама модель будильник не поставит — она только пишет вызов: инструмент и параметры.",
        "The model can't set the alarm itself. It only writes the call: a tool and its parameters.",
    ),
    tr(
        "Будильник ставит приложение — и возвращает результат обратно в разговор.",
        "The app sets the alarm and returns the result to the conversation.",
    ),
    tr(
        "Модель только написала вызов, выполнило его приложение. Ответ — уже после результата.",
        "The model only wrote the call. The app carried it out. The answer comes after the result.",
    ),
)

private fun buttons() = listOf(
    tr("Отправить", "Send"),
    tr("Выполнить в приложении", "Run in the app"),
    tr("Вернуть результат модели", "Return the result to the model"),
    tr("Сначала", "Start over"),
)

/**
 * "A tool call": the user picks an alarm time and walks one call through with a button — the model writes the
 * request, the app executes it, the result returns to the model, and only then does it answer. It shows that
 * the parameters come from your words, while the hands belong to the app.
 */
@Composable
internal fun ToolCallScene() {
    var timeIndex by rememberSaveable { mutableIntStateOf(1) }
    // 0 — not sent yet, 1 — the model wrote the call, 2 — the app returned the result, 3 — the answer.
    var step by rememberSaveable { mutableIntStateOf(0) }
    val time = TIMES[timeIndex]
    val clock = String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
    // The name, the action, the parameters and the result line — as in the app's real alarm tool.
    val contents = listOf(
        "{\"tool\": \"alarm\",\n \"action\": \"set_alarm\",\n \"hour\": ${time.hour}, \"minute\": ${time.minute}}",
        tr("\"Будильник на $clock открыт\n в приложении часов.\"", "\"Alarm for $clock is open\n in the clock app.\""),
        tr("Готово: будильник на ${time.label} стоит!", "Done: the alarm is set for ${time.label}!"),
    )
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = tr("Ты пишешь: «Поставь будильник на ${time.label}»", "You write: “Set an alarm for ${time.label}”"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        // The time can be changed at any step: the call's parameters follow your words right away.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TIMES.forEachIndexed { index, t ->
                FilterChip(selected = index == timeIndex, onClick = { timeIndex = index }, label = { Text(t.label) })
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            contents.forEachIndexed { index, content ->
                if (index > 0) {
                    Text(
                        text = "↓",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (step > index) primary else primary.copy(alpha = 0.25f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CallBox(
                    header = headers()[index],
                    content = content,
                    lines = if (index == 0) 3 else 2,
                    mono = index < 2,
                    shown = step > index,
                    active = step == index + 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (step == 3) primary.copy(alpha = 0.14f) else Color.White)
                .padding(12.dp),
        ) {
            Text(
                text = notes()[step],
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (step == 3) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 3,
            )
        }
        Button(onClick = { step = (step + 1) % buttons().size }, modifier = Modifier.fillMaxWidth()) {
            Text(buttons()[step])
        }
    }
}

/** The box of one step. The space for the text is taken from the start — content appearing moves nothing. */
@Composable
private fun CallBox(header: String, content: String, lines: Int, mono: Boolean, shown: Boolean, active: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val contentAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(350), label = "callContent")
    val border by animateColorAsState(if (active) primary else Color.Transparent, tween(300), label = "callBorder")
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (shown) Color.White else Color.White.copy(alpha = 0.4f))
            .border(2.dp, border, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = header,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (shown) primary else SceneInk.copy(alpha = 0.45f),
        )
        Text(
            text = content,
            color = SceneInk,
            style = if (mono) {
                MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            } else {
                MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
            },
            minLines = lines,
            maxLines = lines,
            modifier = Modifier.alpha(contentAlpha),
        )
    }
}
