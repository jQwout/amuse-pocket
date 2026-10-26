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
import com.aiopenpocket.i18n.tr
import kotlin.math.roundToInt

/**
 * Модель линейки: что берёт на вход и что отдаёт, работает ли в этом приложении и что сказать, когда задача приехала.
 * Тексты — парами ru/en, язык выбирается геттером в момент показа: список инициализируется один раз.
 */
private class LineupModel(
    private val nameRu: String,
    private val nameEn: String,
    private val ioRu: String,
    private val ioEn: String,
    val inApp: Boolean,
    private val noteRu: String,
    private val noteEn: String,
) {
    val name: String get() = tr(nameRu, nameEn)
    val io: String get() = tr(ioRu, ioEn)
    val note: String get() = tr(noteRu, noteEn)
}

private val LINEUP = listOf(
    LineupModel(
        "Muse Spark", "Muse Spark",
        "текст, фото, видео → текст + вызовы инструментов", "text, photos, video → text + tool calls",
        true,
        "Muse Spark — мозг агентов этого приложения: понимает запрос и сам вызывает инструменты",
        "Muse Spark is the brain of this app's agents: it understands the request and calls tools on its own",
    ),
    LineupModel(
        "Muse Image", "Muse Image",
        "текст, картинки → новая картинка", "text, images → a new image",
        true,
        "Muse Image нарисовала картинки к большинству уроков и перерисует их «под тебя» по кнопке",
        "Muse Image drew the pictures for most lessons and will redraw them “for you” at the tap of a button",
    ),
    LineupModel(
        "SAM 3.1", "SAM 3.1",
        "фото или видео + короткая фраза → маски объектов", "photo or video + a short phrase → object masks",
        false,
        "SAM 3.1 находит кота по фразе, обводит маской по пикселям и ведёт его по кадрам",
        "SAM 3.1 finds the cat from a phrase, outlines it with a pixel-precise mask and tracks it across frames",
    ),
    LineupModel(
        "Muse Voice Transcribe", "Muse Voice Transcribe",
        "голос → текст", "voice → text",
        false,
        "Muse Voice Transcribe делает одно дело: превращает голосовое в текст",
        "Muse Voice Transcribe does one thing: turns a voice message into text",
    ),
    LineupModel(
        "Llama — открытые веса", "Llama — open weights",
        "скачал веса → модель работает на твоём железе", "download the weights → the model runs on your hardware",
        false,
        "Llama — открытые веса: их скачивают и запускают у себя, без чужого облака",
        "Llama is open weights: you download them and run them yourself, no one else's cloud",
    ),
)

/** Задача и номер модели, которая её берёт. Текст — парой ru/en, выбирается в момент показа. */
private class LineupJob(private val textRu: String, private val textEn: String, val model: Int) {
    val text: String get() = tr(textRu, textEn)
}

/** Порядок нарочно не совпадает с моделями — задаче есть куда ехать. */
private val JOBS = listOf(
    LineupJob("обвести кота на видео", "outline a cat in a video", 2),
    LineupJob("ответить и вызвать инструмент", "answer and call a tool", 0),
    LineupJob("запустить модель на своём сервере", "run a model on your own server", 4),
    LineupJob("нарисовать иллюстрацию к уроку", "draw a lesson illustration", 1),
    LineupJob("расшифровать голосовое", "transcribe a voice message", 3),
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
        lit = JOBS[f.job].model
        done = done or (1 shl f.job)
        flight = null
    }
    val send = { job: Int ->
        val root = anchors.root
        val src = anchors.jobs[job]
        val dst = anchors.models[JOBS[job].model]
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
            Text(
                tr("Кому из моделей Meta отдать задачу? Нажми на задачу", "Which Meta model gets the job? Tap a task"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            JOBS.indices.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { job ->
                        JobBox(
                            text = JOBS[job].text,
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
                    text = if (lit >= 0) {
                        LINEUP[lit].note
                    } else {
                        tr("Нажми на задачу — она уедет к модели, которая с ней справится", "Tap a task — it will travel to the model that can handle it")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    minLines = 3,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { done = 0; lit = -1 }, enabled = done != 0 && flight == null) { Text(tr("Сначала", "Start over")) }
            }
            AnimatedVisibility(visible = allDone, enter = fadeIn()) {
                Text(
                    tr(
                        "У Meta не одна модель на всё, а команда специалистов — приложение берёт ту, что под задачу",
                        "Meta doesn't have one model for everything but a team of specialists — the app picks the right one for the task",
                    ),
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
                    JOBS[f.job].text,
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
                    tr("в этом приложении", "in this app"),
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
