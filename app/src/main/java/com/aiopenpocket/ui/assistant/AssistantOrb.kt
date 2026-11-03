package com.aiopenpocket.ui.assistant

// @trace voice-assistant-mode-a81a99

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.LimeSignal
import kotlin.math.PI
import kotlin.math.sin

/** What the assistant is doing; the orb shows it: calm, voice bars, or a turning arc. */
enum class OrbState { IDLE, LISTENING, THINKING }

// The app's lime-on-ink palette: a glowing lime core on a near-black sphere.
private val CoreHighlight = Color(0xFFF1FBD0)
private val CoreLime = LimeSignal
private val CoreDeep = Color(0xFF5E7A16)
private val CoreInk = Color(0xFF0B100C)
private val BarLight = Color(0xFFE4F7A8)

/**
 * The assistant's orb, ported from the owner's earlier assistant (openQwoutt `OrbView`) and recoloured:
 * a floating sphere with a halo and three pulse rings; six voice bars while listening; a turning arc
 * while the model thinks. Drawn on one Canvas, so it costs no layout and no assets.
 */
@Composable
fun AssistantOrb(
    state: OrbState,
    modifier: Modifier = Modifier,
    size: Dp = 132.dp,
) {
    val transition = rememberInfiniteTransition(label = "orb")
    val float by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(5000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float",
    )
    val pulses = listOf(0, 933, 1866).map { delay ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2800, delay, easing = LinearEasing)),
            label = "pulse$delay",
        )
    }.map { it.value }
    val bars = listOf(1100 to 0, 850 to 80, 950 to 20, 1050 to 120, 900 to 40, 750 to 60).mapIndexed { i, (dur, delay) ->
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(dur, delay, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "bar$i",
        )
    }.map { it.value }
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing)),
        label = "rotation",
    )

    val active = state != OrbState.IDLE
    val pulseAlpha = if (active) 0.7f else 0.3f
    val pulseMax = if (active) 1.65f else 1.35f

    Canvas(modifier = modifier.size(size)) {
        val orbR = this.size.width * 0.36f
        val center = Offset(
            this.size.width / 2f,
            this.size.height / 2f + sin(float * PI.toFloat()) * orbR * 0.07f,
        )

        drawCircle(
            brush = Brush.radialGradient(listOf(CoreLime.copy(alpha = 0.28f), Color.Transparent), center, orbR * 1.7f),
            radius = orbR * 1.7f,
            center = center,
        )
        pulses.forEach { p ->
            drawCircle(
                color = CoreLime.copy(alpha = pulseAlpha * (1f - p)),
                radius = orbR * (1.05f + p * (pulseMax - 1.05f)),
                center = center,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        if (state == OrbState.THINKING) {
            drawArc(
                color = CoreLime.copy(alpha = 0.6f),
                startAngle = rotation,
                sweepAngle = 260f,
                useCenter = false,
                topLeft = Offset(center.x - orbR * 1.12f, center.y - orbR * 1.12f),
                size = Size(orbR * 2.24f, orbR * 2.24f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        drawCircle(color = CoreInk, radius = orbR, center = center)
        drawCircle(
            brush = Brush.radialGradient(
                listOf(CoreDeep, Color.Transparent),
                Offset(center.x + orbR * 0.1f, center.y + orbR * 0.1f),
                orbR * 0.9f,
            ),
            radius = orbR,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(CoreLime.copy(alpha = 0.8f), Color.Transparent),
                Offset(center.x - orbR * 0.15f, center.y - orbR * 0.15f),
                orbR * 0.7f,
            ),
            radius = orbR,
            center = center,
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(CoreHighlight.copy(alpha = 0.55f), Color.Transparent),
                Offset(center.x - orbR * 0.28f, center.y - orbR * 0.3f),
                orbR * 0.45f,
            ),
            radius = orbR,
            center = center,
        )
        drawCircle(
            color = CoreLime.copy(alpha = 0.25f),
            radius = orbR * 0.9f,
            center = center,
            style = Stroke(width = 1.dp.toPx()),
        )

        if (state == OrbState.LISTENING) {
            clipRect(center.x - orbR, center.y - orbR, center.x + orbR, center.y + orbR) {
                drawVoiceBars(center, orbR, bars)
            }
        }
    }
}

private fun DrawScope.drawVoiceBars(center: Offset, orbR: Float, progress: List<Float>) {
    val barW = orbR * 0.09f
    val gap = orbR * 0.045f
    val startX = center.x - (6 * barW + 5 * gap) / 2f
    val minH = listOf(0.14f, 0.22f, 0.32f, 0.32f, 0.22f, 0.14f)
    val maxH = listOf(0.28f, 0.50f, 0.70f, 0.70f, 0.50f, 0.28f)
    for (i in 0 until 6) {
        val h = (minH[i] + (maxH[i] - minH[i]) * progress[i]) * orbR * 2
        drawRoundRect(
            color = if (i == 0 || i == 5) BarLight else CoreHighlight,
            topLeft = Offset(startX + i * (barW + gap), center.y - h / 2f),
            size = Size(barW, h),
            cornerRadius = CornerRadius(barW / 2f),
        )
    }
}
