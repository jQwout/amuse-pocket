package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Строка панели: что меняется, когда веса переезжают. */
private class WeightsFact(val label: String, val mine: String, val vendor: String)

private val FACTS = listOf(
    WeightsFact("Кто видит данные", "только ты", "ты и Meta"),
    WeightsFact("Что нужно", "видеокарта, сервер", "только ключ API"),
    WeightsFact("Как платишь", "железо и свет", "за токены"),
    WeightsFact("Обновления", "ставишь сам", "делает вендор"),
)

private val Mine = ScenePalette[2]
private val Vendor = ScenePalette[0]
private val Warn = Color(0xFFF4C2C2)

/**
 * «Открытые веса и API»: пользователь переключает, где лежат веса — у него на сервере или у Meta.
 * Запрос бежит точкой от телефона туда, а панели меняются: кто видит данные, что нужно, как платишь,
 * кто обновляет. В режиме API можно включить contributor-версию — и копия запроса уходит в обучение.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OpenWeightsScene() {
    var api by rememberSaveable { mutableStateOf(false) }
    var contributor by rememberSaveable { mutableStateOf(false) }
    var visited by rememberSaveable { mutableIntStateOf(1) } // бит 0 — «у тебя», бит 1 — «у Meta»
    val copy = api && contributor
    // 0..1 — запрос летит к модели, 1..2 — ответ обратно (а при contributor копия уходит в обучение).
    val trip = remember { Animatable(0f) }
    LaunchedEffect(api, copy) {
        trip.snapTo(0f)
        trip.animateTo(2f, tween(1600, easing = LinearEasing))
    }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Где лежат веса модели — у тебя или у Meta?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("У тебя", "У Meta (API)").forEachIndexed { index, label ->
                SegmentedButton(
                    selected = api == (index == 1),
                    onClick = {
                        api = index == 1
                        if (!api) contributor = false
                        visited = visited or (1 shl index)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, 2),
                ) { Text(label, maxLines = 1) }
            }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
            val phone = Offset(size.width * 0.12f, size.height * 0.5f)
            val server = Offset(size.width * 0.62f, size.height * 0.22f)
            val cloud = Offset(size.width * 0.52f, size.height * 0.74f)
            val training = Offset(size.width * 0.86f, size.height * 0.74f)
            val target = if (api) cloud else server
            val faint = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            listOf(server, cloud).forEach { node ->
                if (node == target) drawLine(primary, phone, node, strokeWidth = 3.dp.toPx())
                else drawLine(SceneInk.copy(alpha = 0.2f), phone, node, strokeWidth = faint.width, pathEffect = faint.pathEffect)
            }
            drawLine(
                if (copy) error else SceneInk.copy(alpha = 0.15f), cloud, training,
                strokeWidth = (if (copy) 3 else 2).dp.toPx(),
                pathEffect = if (copy) null else faint.pathEffect,
            )
            drawPhone(phone)
            drawServer(server, active = !api, accent = primary)
            drawCloud(cloud, active = api, accent = primary)
            val landed = copy && trip.value >= 2f
            drawRoundRect(
                if (landed) Warn else Color.White,
                topLeft = training - Offset(22.dp.toPx(), 15.dp.toPx()),
                size = Size(44.dp.toPx(), 30.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
            )
            drawRoundRect(
                if (copy) error else SceneInk.copy(alpha = 0.3f),
                topLeft = training - Offset(22.dp.toPx(), 15.dp.toPx()),
                size = Size(44.dp.toPx(), 30.dp.toPx()),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = if (copy) Stroke(2.dp.toPx()) else faint,
            )
            label(measurer, "телефон", phone + Offset(0f, 24.dp.toPx()))
            label(measurer, "твой сервер", server + Offset(0f, 22.dp.toPx()))
            label(measurer, "облако Meta", cloud + Offset(0f, 20.dp.toPx()))
            label(measurer, "обучение", training + Offset(0f, 18.dp.toPx()))

            // Точка-запрос: туда, потом ответ обратно. Копия при contributor отделяется у облака и уходит вправо.
            val t = trip.value
            if (t < 2f) {
                val dot = if (t <= 1f) lerp(phone, target, t) else lerp(target, phone, t - 1f)
                drawCircle(primary, 6.dp.toPx(), dot)
                if (copy && t > 1f) drawCircle(error, 6.dp.toPx(), lerp(cloud, training, t - 1f))
            }
        }
        FACTS.forEachIndexed { index, fact ->
            val warn = copy && index == 0
            FactRow(fact.label, if (warn) "Meta, может учиться" else if (api) fact.vendor else fact.mine, if (warn) Warn else if (api) Vendor else Mine)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).alpha(if (api) 1f else 0.45f)) {
                Text("contributor-версия", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text("Только у API: вендор может учиться на том, что ты шлёшь", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Switch(checked = copy, onCheckedChange = { contributor = it }, enabled = api)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(2.dp, if (copy) error else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(12.dp),
        ) {
            Text(
                text = when {
                    copy -> "Копия запроса может уйти в обучение модели. Это приложение такие версии отфильтровывает"
                    api -> "Веса у Meta: нужен только ключ, платишь за токены — но вендор видит всё, что ты отправил"
                    else -> "Веса у тебя: данные никуда не уходят, но видеокарта, счёт за свет и обновления — на тебе"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SceneInk,
                minLines = 3,
            )
        }
        AnimatedVisibility(visible = visited == 3, enter = fadeIn()) {
            Text(
                "Где лежат веса — решает, кто видит твои данные и кто делает всю работу",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = primary,
            )
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, tint: Color) {
    val background by animateColorAsState(tint, tween(300), label = "factBg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SceneInk, maxLines = 1, modifier = Modifier.width(124.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = SceneInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun DrawScope.label(measurer: TextMeasurer, text: String, topCenter: Offset) {
    val layout = measurer.measure(text, TextStyle(fontSize = 11.sp, color = SceneInk.copy(alpha = 0.7f)))
    drawText(layout, topLeft = Offset(topCenter.x - layout.size.width / 2f, topCenter.y))
}

private fun DrawScope.drawPhone(center: Offset) {
    val body = Size(24.dp.toPx(), 40.dp.toPx())
    val topLeft = center - Offset(body.width / 2, body.height / 2)
    drawRoundRect(Color.White, topLeft, body, CornerRadius(5.dp.toPx()))
    drawRoundRect(SceneInk, topLeft, body, CornerRadius(5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
    val inset = 3.dp.toPx()
    drawRect(Vendor, topLeft + Offset(inset, inset * 2), Size(body.width - inset * 2, body.height - inset * 4))
}

/** Стойка из трёх юнитов: так рисуют свой сервер с видеокартой. */
private fun DrawScope.drawServer(center: Offset, active: Boolean, accent: Color) {
    val unit = Size(46.dp.toPx(), 11.dp.toPx())
    val gap = 2.dp.toPx()
    repeat(3) { i ->
        val topLeft = Offset(center.x - unit.width / 2, center.y - (unit.height * 1.5f + gap) + i * (unit.height + gap))
        drawRoundRect(Color.White, topLeft, unit, CornerRadius(3.dp.toPx()))
        drawRoundRect(if (active) accent else SceneInk.copy(alpha = 0.5f), topLeft, unit, CornerRadius(3.dp.toPx()), style = Stroke(if (active) 2.dp.toPx() else 1.dp.toPx()))
        drawCircle(if (active) Mine else SceneInk.copy(alpha = 0.2f), 2.5.dp.toPx(), topLeft + Offset(unit.width - 7.dp.toPx(), unit.height / 2))
    }
}

/** Облако из трёх кругов: сначала круги чуть больше цветом контура, потом белые поверх — получается один силуэт. */
private fun DrawScope.drawCloud(center: Offset, active: Boolean, accent: Color) {
    val circles = listOf(
        Offset(-15.dp.toPx(), 4.dp.toPx()) to 11.dp.toPx(),
        Offset(0f, -5.dp.toPx()) to 15.dp.toPx(),
        Offset(16.dp.toPx(), 4.dp.toPx()) to 11.dp.toPx(),
    )
    val edge = if (active) 2.dp.toPx() else 1.dp.toPx()
    circles.forEach { (shift, r) -> drawCircle(if (active) accent else SceneInk.copy(alpha = 0.5f), r + edge, center + shift) }
    circles.forEach { (shift, r) -> drawCircle(Color.White, r, center + shift) }
}
