package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Модель линейки: что берёт на вход и что отдаёт, работает ли в этом приложении и что сказать, когда задача приехала. */
private class LineupModel(val name: String, val io: String, val inApp: Boolean, val note: String)

private val LINEUP = listOf(
    LineupModel(
        "Muse Spark", "текст, фото, видео → текст + вызовы инструментов", true,
        "Muse Spark — мозг агентов этого приложения: понимает запрос и сам вызывает инструменты",
    ),
    LineupModel(
        "Muse Image", "текст, картинки → новая картинка", true,
        "Muse Image нарисовала картинки к большинству уроков и перерисует их «под тебя» по кнопке",
    ),
    LineupModel(
        "SAM 3.1", "фото или видео + короткая фраза → маски объектов", false,
        "SAM 3.1 находит кота по фразе, обводит маской по пикселям и ведёт его по кадрам",
    ),
    LineupModel("Muse Voice Transcribe", "голос → текст", false, "Muse Voice Transcribe делает одно дело: превращает голосовое в текст"),
    LineupModel(
        "Llama — открытые веса", "скачал веса → модель работает на твоём железе", false,
        "Llama — открытые веса: их скачивают и запускают у себя, без чужого облака",
    ),
)

/** Задача и номер модели, которая её берёт. Порядок нарочно не совпадает с моделями — задаче есть куда ехать. */
private val JOBS = listOf(
    "обвести кота на видео" to 2,
    "ответить и вызвать инструмент" to 0,
    "запустить модель на своём сервере" to 4,
    "нарисовать иллюстрацию к уроку" to 1,
    "расшифровать голосовое" to 3,
)

/** Где на экране лежат карточки — чтобы задача летела от своей кнопки к своей модели. Не состояние: только для расчёта полёта. */
private class LineupAnchors {
    var root: LayoutCoordinates? = null
    val jobs = arrayOfNulls<LayoutCoordinates>(JOBS.size)
    val models = arrayOfNulls<LayoutCoordinates>(LINEUP.size)
}

private class LineupFlight(val job: Int, val from: Offset, val to: Offset)

/**
 * «Модели Meta»: пользователь нажимает на задачу, и она едет к модели, которая с ней справится; карточка
 * модели загорается и показывает, что берёт на вход и что отдаёт. Отметка «в этом приложении» — у двух
 * моделей, на которых работает приложение. Пять задач — пять разных специалистов.
 */
@Composable
internal fun MetaLineupScene() {
    var done by rememberSaveable { mutableIntStateOf(0) } // битовая маска задач, уже отданных модели
    var lit by rememberSaveable { mutableIntStateOf(-1) }
    var flight by remember { mutableStateOf<LineupFlight?>(null) }
    val anchors = remember { LineupAnchors() }
    val travel = remember { Animatable(0f) }
    LaunchedEffect(flight) {
        val f = flight ?: return@LaunchedEffect
        lit = -1
        travel.snapTo(0f)
        travel.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
        lit = JOBS[f.job].second
        done = done or (1 shl f.job)
        flight = null
    }
    val send = { job: Int ->
        val root = anchors.root
        val src = anchors.jobs[job]
        val dst = anchors.models[JOBS[job].second]
        if (flight == null && root != null && src != null && dst != null && root.isAttached && src.isAttached && dst.isAttached) {
            val from = root.localPositionOf(src, Offset.Zero) + Offset(src.size.width / 2f, src.size.height / 2f)
            val to = root.localPositionOf(dst, Offset.Zero) + Offset(dst.size.width * 0.7f, dst.size.height / 2f)
            flight = LineupFlight(job, from, to)
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val allDone = Integer.bitCount(done) == JOBS.size

    Box(modifier = Modifier.fillMaxWidth().onGloballyPositioned { anchors.root = it }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Кому из моделей Meta отдать задачу? Нажми на задачу", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            JOBS.indices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { job ->
                        JobBox(
                            text = JOBS[job].first,
                            done = done and (1 shl job) != 0,
                            away = flight?.job == job,
                            onClick = { send(job) },
                            modifier = Modifier.weight(1f).onGloballyPositioned { anchors.jobs[job] = it },
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            LINEUP.forEachIndexed { index, model ->
                ModelCard(
                    model = model,
                    lit = lit == index,
                    color = ScenePalette[index % ScenePalette.size],
                    modifier = Modifier.onGloballyPositioned { anchors.models[index] = it },
                )
            }
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (lit >= 0) LINEUP[lit].note else "Нажми на задачу — она уедет к модели, которая с ней справится",
                    style = MaterialTheme.typography.bodyMedium,
                    minLines = 3,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { done = 0; lit = -1 }, enabled = done != 0 && flight == null) { Text("Сначала") }
            }
            AnimatedVisibility(visible = allDone, enter = fadeIn()) {
                Text(
                    "У Meta не одна модель на всё, а команда специалистов — приложение берёт ту, что под задачу",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                )
            }
        }
        // Летящая задача поверх карточек: от кнопки к модели, к концу пути чуть уменьшается — «въезжает» в карточку.
        flight?.let { f ->
            val p = travel.value
            val at = lerp(f.from, f.to, p)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset((at.x - 75.dp.toPx()).roundToInt(), (at.y - 20.dp.toPx()).roundToInt()) }
                    .graphicsLayer { scaleX = 1f - 0.3f * p; scaleY = 1f - 0.3f * p }
                    .size(width = 150.dp, height = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primary)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    JOBS[f.job].first,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun JobBox(text: String, done: Boolean, away: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val background by animateColorAsState(if (done) ScenePalette[2] else Color.White, label = "jobBg")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(52.dp)
            .alpha(if (away) 0.35f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = SceneInk, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun ModelCard(model: LineupModel, lit: Boolean, color: Color, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val background by animateColorAsState(if (lit) color else Color.White, tween(300), label = "modelBg")
    val edge by animateColorAsState(if (lit) primary else Color.Transparent, tween(300), label = "modelEdge")
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(2.dp, edge, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(model.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = SceneInk, maxLines = 1, modifier = Modifier.weight(1f))
            if (model.inApp) {
                Text(
                    "в этом приложении",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(primary).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        // Вход → выход виден всегда, но бледно: загорается вместе с карточкой, когда к ней приехала задача.
        Text(model.io, style = MaterialTheme.typography.bodySmall, color = SceneInk.copy(alpha = if (lit) 1f else 0.55f))
    }
}
