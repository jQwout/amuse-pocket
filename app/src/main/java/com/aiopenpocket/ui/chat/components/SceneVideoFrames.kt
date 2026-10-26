package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlin.math.PI
import kotlin.math.sin

// The scene's clip: 4 seconds, the cup is lifted for 0.3 seconds. Exactly enough that at 1–2 frames per second
// the lift slips between frames, and at 4 it gets into a frame.
private const val CLIP_SECONDS = 4f
private const val LIFT_START = 1.55f
private const val LIFT_END = 1.85f
/** Played twice as slowly: otherwise the 0.3 seconds of the lift are too quick to notice on a phone. */
private const val SLOWDOWN = 2
/** Illustrative: real models have their own price per frame; what matters is the order of magnitude — every frame is hundreds of tokens. */
private const val TOKENS_PER_FRAME = 256
private val FPS_OPTIONS = listOf(1, 2, 4)

private val Wall = Color(0xFFF4F1EA)
private val Table = Color(0xFFB98B5E)
private val Cup = Color(0xFF5B8DEF)
private val LiftBand = Color(0xFFF2994A)
private val Caught = Color(0xFF2E7D32)

/**
 * "How a model sees": the clip loops, and the user picks how many frames per second the model takes. The main
 * thing is the timeline under the clip: the orange bar is when the cup was lifted, the ticks are when the model
 * takes a frame. If no tick falls into the bar, there was no lift for the model. Few frames — cheap, but
 * something fast slips through; many — noticed, but more expensive.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VideoFramesScene() {
    var fps by rememberSaveable { mutableIntStateOf(1) }
    val time by rememberInfiniteTransition(label = "clip").animateFloat(
        initialValue = 0f,
        targetValue = CLIP_SECONDS,
        animationSpec = infiniteRepeatable(tween((CLIP_SECONDS * 1000 * SLOWDOWN).toInt(), easing = LinearEasing)),
        label = "clipTime",
    )
    val frames = (0 until (CLIP_SECONDS * fps).toInt()).map { it.toFloat() / fps }
    val caught = frames.any { it in LIFT_START..LIFT_END }
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            tr("Ролик на 4 секунды: на миг поднимают чашку (показано медленнее)", "A 4-second clip: a cup is lifted for a moment (shown slowed down)"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        CupFrame(time = time, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Timeline(time = time, frames = frames, primary = primary, modifier = Modifier.fillMaxWidth().height(64.dp))
        Text(
            tr(
                "Чёрточки — моменты, когда модель берёт кадр. Всё, что случилось между ними, для неё не существует.",
                "Each tick is a moment when the model grabs a frame. Whatever happens between them doesn't exist for it.",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FPS_OPTIONS.forEach { option ->
                FilterChip(selected = fps == option, onClick = { fps = option }, label = { Text(tr("$option в сек", "$option per sec")) })
            }
        }
        Text(tr("Что увидела модель:", "What the model saw:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            frames.forEach { frameTime ->
                CupFrame(
                    time = frameTime,
                    modifier = Modifier.size(width = 52.dp, height = 30.dp),
                    highlight = frameTime in LIFT_START..LIFT_END,
                )
            }
        }
        Text(
            text = tr(
                "Кадров: ${frames.size} · токенов ≈ ${frames.size * TOKENS_PER_FRAME}",
                "Frames: ${frames.size} · tokens ≈ ${frames.size * TOKENS_PER_FRAME}",
            ),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (caught) {
                tr(
                    "Одна чёрточка попала в подъём — модель его увидит, но токенов в ${fps} раза больше",
                    "One tick landed on the lift — the model will see it, but it costs ${fps}× the tokens",
                )
            } else {
                tr(
                    "Ни одна чёрточка не попала в подъём — для модели чашку никто не поднимал",
                    "No tick landed on the lift — as far as the model knows, nobody lifted the cup",
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (caught) Caught else MaterialTheme.colorScheme.error,
        )
    }
}

/** The 0–4 s scale: the lift bar, the model's frame ticks and the running "now" mark. */
@Composable
private fun Timeline(time: Float, frames: List<Float>, primary: Color, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier = modifier) {
        val left = 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        fun x(t: Float) = left + (right - left) * (t / CLIP_SECONDS)
        val lineY = size.height * 0.55f

        // The "cup lifted" bar and the caption above it.
        drawRoundRect(
            color = LiftBand.copy(alpha = 0.85f),
            topLeft = Offset(x(LIFT_START), lineY - 9.dp.toPx()),
            size = Size(x(LIFT_END) - x(LIFT_START), 18.dp.toPx()),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        val bandLabel = measurer.measure(tr("чашку подняли", "cup lifted"), TextStyle(fontSize = 11.sp, color = LiftBand, fontWeight = FontWeight.SemiBold))
        drawText(bandLabel, topLeft = Offset(x((LIFT_START + LIFT_END) / 2) - bandLabel.size.width / 2, 0f))

        drawLine(SceneInk.copy(alpha = 0.35f), Offset(left, lineY), Offset(right, lineY), strokeWidth = 2.dp.toPx())

        // Frame ticks: green if they hit the lift.
        frames.forEach { t ->
            val inLift = t in LIFT_START..LIFT_END
            drawLine(
                color = if (inLift) Caught else primary,
                start = Offset(x(t), lineY - 13.dp.toPx()),
                end = Offset(x(t), lineY + 13.dp.toPx()),
                strokeWidth = (if (inLift) 4 else 3).dp.toPx(),
            )
        }

        // The seconds under the scale.
        (0..CLIP_SECONDS.toInt()).forEach { s ->
            val label = measurer.measure(tr("${s}с", "${s}s"), TextStyle(fontSize = 10.sp, color = SceneInk.copy(alpha = 0.55f)))
            drawText(label, topLeft = Offset(x(s.toFloat()) - label.size.width / 2, lineY + 15.dp.toPx()))
        }

        // Where the clip is now.
        drawCircle(SceneInk, radius = 5.dp.toPx(), center = Offset(x(time), lineY))
    }
}

@Composable
private fun CupFrame(time: Float, modifier: Modifier, highlight: Boolean = false) {
    val shape = RoundedCornerShape(6.dp)
    Canvas(
        modifier = modifier
            .clip(shape)
            .background(Wall)
            .border(if (highlight) 3.dp else 0.dp, if (highlight) Caught else Color.Transparent, shape),
    ) {
        val tableY = size.height * 0.78f
        drawRect(Table, topLeft = Offset(0f, tableY), size = Size(size.width, size.height - tableY))
        // A window on the wall — so the frame reads as a room, not a diagram.
        drawRect(
            Color(0xFFCFE3F3),
            topLeft = Offset(size.width * 0.08f, size.height * 0.12f),
            size = Size(size.width * 0.22f, size.height * 0.34f),
        )
        val lift = liftAmount(time)
        val cupW = size.width * 0.13f
        val cupH = size.height * 0.26f
        val x = size.width * 0.5f - cupW / 2
        val y = tableY - cupH - lift * size.height * 0.45f
        // The shadow stays on the table — it shows that the cup was really lifted, not redrawn higher.
        if (lift > 0f) {
            drawOval(
                Color.Black.copy(alpha = 0.18f * (1f - lift * 0.5f)),
                topLeft = Offset(x - cupW * 0.1f, tableY - cupH * 0.08f),
                size = Size(cupW * 1.2f, cupH * 0.16f),
            )
        }
        drawRoundRect(Cup, topLeft = Offset(x, y), size = Size(cupW, cupH), cornerRadius = CornerRadius(cupW * 0.15f))
        drawArc(
            color = Cup,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(x + cupW - cupW * 0.25f, y + cupH * 0.2f),
            size = Size(cupW * 0.5f, cupH * 0.5f),
            style = Stroke(width = cupW * 0.12f),
        )
    }
}

/** 0 — the cup is on the table, 1 — lifted highest; smoothly up and down within the lift window. */
private fun liftAmount(t: Float): Float {
    if (t !in LIFT_START..LIFT_END) return 0f
    val p = (t - LIFT_START) / (LIFT_END - LIFT_START)
    return sin(p * PI).toFloat()
}
