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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiopenpocket.i18n.AppLanguage
import com.aiopenpocket.i18n.tr
import kotlin.random.Random

/** A plan box: a caption and a place on the canvas in fractions of the width and the height. The caption is on the right so it does not overlap the neighbour. */
private class SketchPart(val label: String, val rect: Rect, val tagRight: Boolean = false)

/** The layout of one "throw": the plan boxes, the area the check will redraw, and the drops for it. */
private class SketchPicture(val parts: List<SketchPart>, val fix: Rect, val drops: List<Offset>)

private class SketchPrompt(val chip: String, val text: String, val issue: String, val fixNote: String)

// A getter, not a stored list: tr() has to run when the scene is drawn, so a language switch is seen at once.
private val PROMPTS: List<SketchPrompt>
    get() = listOf(
        SketchPrompt(
            tr("Кот и дождь", "Cat in the rain"),
            tr("кот на подоконнике в дождь", "a cat on a windowsill in the rain"),
            tr("нет капель!", "no raindrops!"),
            tr(
                "В промпте дождь, а капель нет — модель перерисовала только окно, остальное не тронула",
                "The prompt says rain, but there are no drops — the model redrew only the window and left the rest alone",
            ),
        ),
        SketchPrompt(
            tr("Маяк на закате", "Sunset lighthouse"),
            tr("маяк на закате", "a lighthouse at sunset"),
            tr("небо дневное!", "daytime sky!"),
            tr(
                "В промпте закат, а небо дневное — модель перерисовала только небо, маяк и море не тронула",
                "The prompt says sunset, but the sky is daytime — the model redrew only the sky and left the lighthouse and sea alone",
            ),
        ),
    )

private fun Random.between(from: Float, to: Float) = from + nextFloat() * (to - from)

private fun catSketch(seed: Int): SketchPicture {
    val rnd = Random(seed)
    val w = rnd.between(0.40f, 0.52f)
    val x = rnd.between(0.05f, 0.95f - w)
    val y = rnd.between(0.06f, 0.14f)
    val window = Rect(x, y, x + w, y + rnd.between(0.50f, 0.58f))
    val sill = Rect((window.left - 0.05f).coerceAtLeast(0f), window.bottom, (window.right + 0.05f).coerceAtMost(1f), window.bottom + 0.07f)
    val catW = rnd.between(0.15f, 0.2f)
    val catLeft = rnd.nextBoolean()
    val catX = if (catLeft) sill.left + 0.02f else sill.right - catW - 0.02f
    val glass = Rect(window.left + 0.02f, window.top + 0.03f, window.right - 0.02f, window.bottom - 0.03f)
    val parts = mutableListOf(
        SketchPart(tr("окно", "window"), window), SketchPart(tr("подоконник", "sill"), sill),
        SketchPart(tr("кот", "cat"), Rect(catX, sill.top - 0.3f, catX + catW, sill.top)),
        SketchPart(tr("капли", "drops"), glass, tagRight = true),
    )
    if (rnd.nextBoolean()) {
        val px = if (catLeft) sill.right - 0.1f else sill.left + 0.03f
        parts += SketchPart(tr("цветок", "flower"), Rect(px, sill.top - 0.17f, px + 0.07f, sill.top))
    }
    return SketchPicture(parts, glass, List(18) { Offset(rnd.nextFloat(), rnd.nextFloat()) })
}

private fun lighthouseSketch(seed: Int): SketchPicture {
    val rnd = Random(seed)
    val horizon = rnd.between(0.55f, 0.66f)
    val towerW = rnd.between(0.09f, 0.12f)
    val towerX = rnd.between(0.08f, 0.85f - towerW)
    // The sun is on the other side from the lighthouse. Fractions 0.05 × 0.08 on a 16:10 canvas give an even circle.
    val sunX = if (towerX > 0.45f) rnd.between(0.15f, 0.35f) else rnd.between(0.6f, 0.85f)
    val sunY = horizon - rnd.between(0.02f, 0.1f)
    val sky = Rect(0f, 0f, 1f, horizon)
    val parts = mutableListOf(
        SketchPart(tr("небо: закат", "sky: sunset"), sky), SketchPart(tr("море", "sea"), Rect(0f, horizon, 1f, 1f), tagRight = true),
        SketchPart(tr("маяк", "lighthouse"), Rect(towerX, rnd.between(0.12f, 0.22f), towerX + towerW, horizon + 0.14f)),
        SketchPart(tr("солнце", "sun"), Rect(sunX - 0.05f, sunY - 0.08f, sunX + 0.05f, sunY + 0.08f)),
    )
    if (rnd.nextBoolean()) {
        val bx = if (towerX > 0.45f) rnd.between(0.05f, 0.3f) else rnd.between(0.55f, 0.8f)
        parts += SketchPart(tr("лодка", "boat"), Rect(bx, horizon + 0.12f, bx + 0.12f, horizon + 0.26f))
    }
    return SketchPicture(parts, sky, emptyList())
}

/**
 * "How Muse Image draws": the user leads the model through the steps. "Plan" — composition boxes with
 * captions, "Drawing" — the boxes become a picture, "Check" — the model notices what from the prompt is missing
 * and redraws only that area. "Again" — the same prompt, but a new plan and a new picture.
 */
@Composable
internal fun ImageReasoningScene() {
    var promptIndex by rememberSaveable { mutableIntStateOf(0) }
    var stage by rememberSaveable { mutableIntStateOf(0) } // 0 canvas, 1 plan, 2 drawing, 3 check
    var seed by rememberSaveable { mutableIntStateOf(7) }
    var variant by rememberSaveable { mutableIntStateOf(1) }
    var checked by rememberSaveable { mutableStateOf(false) }
    val phase = remember { Animatable(0f) }
    LaunchedEffect(stage, seed) {
        phase.snapTo(0f)
        if (stage > 0) phase.animateTo(1f, tween(listOf(0, 1000, 1300, 1800)[stage], easing = LinearEasing))
    }
    val prompt = PROMPTS[promptIndex]
    val picture = remember(promptIndex, seed, AppLanguage.code) { if (promptIndex == 0) catSketch(seed) else lighthouseSketch(seed) }
    val p = phase.value
    // "Drawing": for the first third the plan boxes are still there — it shows which plan is being drawn, then they melt into the picture.
    val reveal = if (stage == 2) ((p - 0.3f) / 0.7f).coerceIn(0f, 1f) else if (stage == 3) 1f else 0f
    val fixK = if (stage == 3) ((p - 0.35f) / 0.65f).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PROMPTS.forEachIndexed { index, item ->
                FilterChip(selected = promptIndex == index, onClick = { promptIndex = index; stage = 0 }, label = { Text(item.chip) })
            }
        }
        Text(tr("Промпт: «${prompt.text}»", "Prompt: “${prompt.text}”"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(Color.White)) {
            // The picture layer: it shows up at the "Drawing" step.
            Canvas(modifier = Modifier.matchParentSize().alpha(reveal)) {
                if (promptIndex == 0) drawCat(picture, fixK) else drawLighthouse(picture, fixK)
            }
            // The plan and check layer: boxes one by one, then they melt; at the check — a frame around what is wrong.
            Canvas(modifier = Modifier.matchParentSize()) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
                picture.parts.forEachIndexed { i, part ->
                    val a = when (stage) { 1 -> (p * picture.parts.size - i).coerceIn(0f, 1f); 2 -> 1f - reveal; else -> 0f }
                    if (a > 0f) {
                        val r = part.rect.px(size)
                        drawRoundRect(primary.copy(alpha = 0.08f * a), r.topLeft, r.size, CornerRadius(6.dp.toPx()))
                        drawRoundRect(primary.copy(alpha = a), r.topLeft, r.size, CornerRadius(6.dp.toPx()), style = Stroke(1.5.dp.toPx(), pathEffect = dash))
                        tag(measurer, part.label, r, part.tagRight, SceneInk, a)
                    }
                }
                if (stage == 3) {
                    val r = picture.fix.px(size)
                    val fixed = p >= 1f
                    drawRect(if (fixed) primary else error, r.topLeft, r.size, style = Stroke(2.5.dp.toPx(), pathEffect = if (fixed) null else dash))
                    if (fixK > 0f && !fixed) {
                        val y = r.top + r.height * fixK
                        drawLine(primary, Offset(r.left, y), Offset(r.right, y), strokeWidth = 2.dp.toPx())
                    }
                    tag(measurer, if (fixed) tr("исправлено только здесь", "fixed only here") else prompt.issue, r, false, if (fixed) primary else error, 1f)
                }
            }
            if (stage == 0) {
                Text(
                    tr("Пустой холст: модель ещё не начала", "Empty canvas: the model hasn't started yet"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SceneInk.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    stage = if (stage == 3) 0 else stage + 1
                    if (stage == 3) checked = true
                },
                modifier = Modifier.weight(1f),
            ) { Text(listOf(tr("1 · План", "1 · Plan"), tr("2 · Рисунок", "2 · Drawing"), tr("3 · Проверка", "3 · Check"), tr("Сначала", "Start over"))[stage]) }
            // A new random seed — a new plan, and a drawing from it right away: the same prompt, a different picture.
            OutlinedButton(onClick = { seed = Random.nextInt(); variant++; stage = 2 }) { Text(tr("Ещё раз", "Again")) }
        }
        Text(
            text = when (stage) {
                0 -> tr("Нажми «План»: Muse Image не рисует сразу, а сначала раскладывает, где что будет", "Tap “Plan”: Muse Image doesn't draw right away, it first lays out what goes where")
                1 -> tr("План: где что встанет. Это набросок композиции, ещё не картинка", "The plan: what goes where. It's a sketch of the composition, not a picture yet")
                2 -> if (variant > 1) {
                    tr("Вариант $variant: промпт тот же — а план и картинка новые. Проверим?", "Version $variant: same prompt — new plan and new picture. Shall we check?")
                } else {
                    tr("Рисунок по плану готов. Но всё ли из промпта на месте?", "The drawing from the plan is ready. But is everything from the prompt there?")
                }
                else -> if (p < 1f) tr("Проверка: модель сверяет свой рисунок с промптом…", "Check: the model compares its drawing with the prompt…") else prompt.fixNote
            },
            style = MaterialTheme.typography.bodyMedium,
            minLines = 3,
        )
        AnimatedVisibility(visible = checked, enter = fadeIn()) {
            Text(
                tr(
                    "Muse Image планирует, рисует и проверяет себя — а тот же промпт каждый раз даёт новую картинку",
                    "Muse Image plans, draws and checks itself — and the same prompt gives a new picture every time",
                ),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = primary,
            )
        }
    }
}

private fun Rect.px(s: Size) = Rect(left * s.width, top * s.height, right * s.width, bottom * s.height)

/** A caption on a white plate at the box's corner; it does not stick out of the canvas. */
private fun DrawScope.tag(measurer: TextMeasurer, text: String, box: Rect, right: Boolean, color: Color, alpha: Float) {
    val layout = measurer.measure(text, TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color))
    val pad = 4.dp.toPx()
    val w = layout.size.width + pad * 2
    val h = layout.size.height + pad
    val x = (if (right) box.right - w - 3.dp.toPx() else box.left + 3.dp.toPx()).coerceIn(0f, size.width - w)
    val y = (box.top + 3.dp.toPx()).coerceAtMost(size.height - h)
    drawRoundRect(Color.White.copy(alpha = 0.92f * alpha), Offset(x, y), Size(w, h), CornerRadius(4.dp.toPx()))
    drawText(layout, topLeft = Offset(x + pad, y + pad / 2), alpha = alpha)
}

private val Fur = Color(0xFFE39B4F)
private val Wood = Color(0xFF8C6A4F)

private fun DrawScope.drawCat(picture: SketchPicture, fixK: Float) {
    drawRect(Color(0xFFF3EBDD))
    val window = picture.parts[0].rect.px(size)
    val glass = picture.fix.px(size)
    drawRect(Wood, window.topLeft, window.size)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF7F95A8), Color(0xFFB4C4D0)), glass.top, glass.bottom), glass.topLeft, glass.size)
    // Only the check draws the drops — and only in its area, top to bottom.
    if (fixK > 0f) {
        clipRect(glass.left, glass.top, glass.right, glass.top + glass.height * fixK) {
            picture.drops.forEach { d ->
                val at = Offset(glass.left + d.x * glass.width, glass.top + d.y * glass.height)
                drawLine(Color.White.copy(alpha = 0.85f), at, at + Offset(-3.dp.toPx(), 9.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
    drawLine(Wood, Offset(glass.center.x, glass.top), Offset(glass.center.x, glass.bottom), strokeWidth = 4.dp.toPx())
    drawLine(Wood, Offset(glass.left, glass.center.y), Offset(glass.right, glass.center.y), strokeWidth = 4.dp.toPx())
    val sill = picture.parts[1].rect.px(size)
    drawRect(Color(0xFFB98B5E), sill.topLeft, sill.size)
    picture.parts.getOrNull(4)?.rect?.px(size)?.let { pot ->
        drawRect(Color(0xFFC8664A), Offset(pot.left, pot.top + pot.height * 0.55f), Size(pot.width, pot.height * 0.45f))
        drawCircle(Color(0xFF6BAF6B), pot.width * 0.45f, Offset(pot.center.x, pot.top + pot.height * 0.35f))
    }
    val c = picture.parts[2].rect.px(size)
    drawOval(Fur, Offset(c.left, c.top + c.height * 0.4f), Size(c.width, c.height * 0.6f))
    drawArc(
        Fur, startAngle = 90f, sweepAngle = -180f, useCenter = false,
        topLeft = Offset(c.right - c.width * 0.2f, c.top + c.height * 0.45f), size = Size(c.width * 0.35f, c.height * 0.5f),
        style = Stroke(width = c.width * 0.1f, cap = StrokeCap.Round),
    )
    val head = Offset(c.left + c.width * 0.32f, c.top + c.height * 0.36f)
    val r = c.width * 0.26f
    val ears = Path().apply {
        moveTo(head.x - r * 0.9f, head.y - r * 0.3f); lineTo(head.x - r * 0.6f, head.y - r * 1.45f); lineTo(head.x - r * 0.05f, head.y - r * 0.85f); close()
        moveTo(head.x + r * 0.9f, head.y - r * 0.3f); lineTo(head.x + r * 0.6f, head.y - r * 1.45f); lineTo(head.x + r * 0.05f, head.y - r * 0.85f); close()
    }
    drawPath(ears, Fur)
    drawCircle(Fur, r, head)
    drawCircle(SceneInk, r * 0.12f, head + Offset(-r * 0.38f, -r * 0.05f))
    drawCircle(SceneInk, r * 0.12f, head + Offset(r * 0.38f, -r * 0.05f))
}

private fun DrawScope.drawLighthouse(picture: SketchPicture, fixK: Float) {
    val sky = picture.fix.px(size)
    val sun = picture.parts[3].rect.px(size)
    drawRect(Brush.verticalGradient(listOf(Color(0xFF7DB9E8), Color(0xFFCFE8F7)), sky.top, sky.bottom), sky.topLeft, sky.size)
    drawCircle(Color(0xFFFFF1A8), sun.width / 2, sun.center)
    // The check recolors only the sky: the lighthouse and the sea are drawn on top and do not change.
    if (fixK > 0f) {
        clipRect(sky.left, sky.top, sky.right, sky.top + sky.height * fixK) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF7B5EA7), Color(0xFFF08A5D), Color(0xFFF9D976)), sky.top, sky.bottom), sky.topLeft, sky.size)
            drawCircle(Color(0xFFF2643A), sun.width / 2, sun.center)
        }
    }
    val sea = picture.parts[1].rect.px(size)
    drawRect(Color(0xFF3E7CB1), sea.topLeft, sea.size)
    repeat(3) { i ->
        val y = sea.top + sea.height * (0.3f + i * 0.25f)
        drawLine(Color.White.copy(alpha = 0.3f), Offset(size.width * (0.1f + i * 0.2f), y), Offset(size.width * (0.3f + i * 0.2f), y), strokeWidth = 2.dp.toPx())
    }
    picture.parts.getOrNull(4)?.rect?.px(size)?.let { b ->
        drawPath(Path().apply { moveTo(b.center.x, b.top); lineTo(b.center.x, b.bottom - b.height * 0.35f); lineTo(b.right - b.width * 0.1f, b.bottom - b.height * 0.35f); close() }, Color.White)
        drawRect(Color(0xFF8C5A3C), Offset(b.left, b.bottom - b.height * 0.3f), Size(b.width, b.height * 0.25f))
    }
    val t = picture.parts[2].rect.px(size)
    drawOval(Color(0xFF6E6A66), Offset(t.left - t.width * 0.6f, t.bottom - t.height * 0.1f), Size(t.width * 2.2f, t.height * 0.18f))
    val inset = t.width * 0.15f
    val bodyTop = t.top + t.height * 0.18f
    val body = Path().apply {
        moveTo(t.left, t.bottom - t.height * 0.05f); lineTo(t.right, t.bottom - t.height * 0.05f)
        lineTo(t.right - inset, bodyTop); lineTo(t.left + inset, bodyTop); close()
    }
    drawPath(body, Color.White)
    clipPath(body) {
        listOf(0.3f, 0.6f).forEach { f -> drawRect(Color(0xFFD9534F), Offset(t.left, t.top + t.height * f), Size(t.width, t.height * 0.12f)) }
    }
    drawPath(body, SceneInk.copy(alpha = 0.3f), style = Stroke(1.dp.toPx()))
    drawRect(Color(0xFFFFD54F), Offset(t.left + inset, t.top + t.height * 0.08f), Size(t.width - inset * 2, t.height * 0.1f))
    drawPath(
        Path().apply { moveTo(t.left + inset * 0.5f, t.top + t.height * 0.08f); lineTo(t.right - inset * 0.5f, t.top + t.height * 0.08f); lineTo(t.center.x, t.top); close() },
        Color(0xFF3B3B3B),
    )
}
