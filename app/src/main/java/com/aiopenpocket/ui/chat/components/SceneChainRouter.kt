package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.delay

// The lesson's chain: theses → your check → a text from the theses.
// Functions, not top-level vals: tr() has to run when the scene is shown, in the current language.
private fun chainStations() = listOf(
    tr("Шаг 1 · модель", "Step 1 · model") to tr("«составь тезисы статьи про наш продукт»", "“draft the key points of an article about our product”"),
    tr("Проверка · ты", "Check · you") to tr("прочитай тезисы и вычеркни лишнее", "read the key points and cross out the extra"),
    tr("Шаг 2 · модель", "Step 2 · model") to tr("«разверни эти тезисы в текст»", "“expand these key points into the text”"),
)
private const val STATION_H = 100
private const val STATION_GAP = 10
private const val PACKET_H = 40

/** Requests, the router's label and the branch it sends them to. The branches are the lesson's: by difficulty and by topic. */
private class RoutedAsk(val text: String, val tag: String, val lane: Int, val result: String)

private fun routerLanes() = listOf(
    tr("Быстрая модель", "Fast model") to tr("дёшево", "cheap"),
    tr("Ветка «доставка»", "Delivery branch") to tr("свой промпт", "own prompt"),
    tr("Большая модель", "Big model") to tr("дороже, умнее", "pricier, smarter"),
)
private fun routerAsks() = listOf(
    RoutedAsk(
        tr("Сколько будет 2+2?", "What is 2+2?"), tr("простое", "simple"), 0,
        tr("Незачем гонять дорогую модель на «2+2» — сортировка экономит деньги и секунды", "No need to run an expensive model on “2+2”: sorting saves money and seconds"),
    ),
    RoutedAsk(
        tr("Где мой заказ?", "Where is my order?"), tr("доставка", "delivery"), 1,
        tr("Вопрос про доставку — в свою ветку со своим промптом: точнее, чем один промпт на всё", "A delivery question goes to its own branch with its own prompt: more precise than one prompt for everything"),
    ),
    RoutedAsk(
        tr("Объясните пункт 4.2 договора", "Explain clause 4.2 of the contract"), tr("сложное", "hard"), 2,
        tr("Сложное — к большой модели. А внутри ветки своя цепочка: найти пункт → объяснить", "Hard requests go to the big model. And inside the branch runs its own chain: find the clause → explain it"),
    ),
)

/**
 * "Chain and routing" on two tabs. In the chain the user taps "Next", and the same sheet slides from station
 * to station: a step's output becomes the next one's input, and at the check an extra thesis can be crossed
 * out. In routing you pick an incoming request — the router labels it and sends it to its branch.
 */
@Composable
internal fun ChainRouterScene() {
    var routing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !routing, onClick = { routing = false }, label = { Text(tr("Цепочка", "Chain")) })
            FilterChip(selected = routing, onClick = { routing = true }, label = { Text(tr("Роутинг", "Routing")) })
        }
        if (routing) RouterPart() else ChainPart()
    }
}

@Composable
private fun ChainPart() {
    // 0 — waiting, 1 — the theses are ready, 2 — at the check, 3 — reached step 2, 4 — the text is ready.
    var phase by rememberSaveable { mutableIntStateOf(0) }
    var struck by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == 3) {
            delay(900) // the sheet first arrives as input — and only then turns into text
            phase = 4
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val station = (phase - 1).coerceIn(0, 2)
    val packetY by animateDpAsState(
        (station * (STATION_H + STATION_GAP) + STATION_H - 10 - PACKET_H).dp,
        tween(600),
        label = "packetY",
    )
    val packet = if (phase == 4) {
        buildAnnotatedString {
            append(
                if (struck) {
                    tr("Статья: что умеет продукт и кому он нужен — без пустых обещаний", "Article: what the product does and who needs it, no empty promises")
                } else {
                    tr("Статья: что умеет продукт, кому он нужен и почему он «лучший на рынке»", "Article: what the product does, who needs it and why it's “the best on the market”")
                },
            )
        }
    } else {
        buildAnnotatedString {
            append(tr("Тезисы: 1) что умеет продукт 2) кому он нужен ", "Key points: 1) what the product does 2) who needs it "))
            // A doubtful thesis is marked red: that is the one to catch at the check.
            withStyle(SpanStyle(color = error, textDecoration = if (struck) TextDecoration.LineThrough else null)) {
                append(tr("3) «лучший на рынке»", "3) “the best on the market”"))
            }
        }
    }

    Text(tr("Задача: «напиши статью про наш продукт» — по шагам", "Task: “write an article about our product,” step by step"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Box(modifier = Modifier.fillMaxWidth().height((3 * STATION_H + 2 * STATION_GAP).dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(STATION_GAP.dp)) {
            chainStations().forEachIndexed { index, (role, task) ->
                val active = phase > 0 && station == index
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(STATION_H.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(2.dp, if (active) primary else Color.Transparent, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    Text(role, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = if (active) primary else SceneInk)
                    Text(task, style = MaterialTheme.typography.bodySmall, color = SceneInk, maxLines = 1)
                }
            }
        }
        // The same sheet travels down: what came out of a station is what went into the next one.
        if (phase > 0) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .offset(y = packetY)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(PACKET_H.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (phase == 4) ScenePalette[2] else ScenePalette[4])
                    .padding(horizontal = 8.dp),
            ) {
                Text(packet, style = MaterialTheme.typography.bodySmall, color = SceneInk, maxLines = 2)
            }
        }
    }
    Text(
        text = when {
            phase < 4 -> " "
            struck -> tr("Промах поймали на проверке — до того, как на него потратился шаг 2. Каждый шаг маленький и проверяемый", "The slip was caught at the check, before step 2 spent effort on it. Every step is small and checkable")
            else -> tr("Пустое «лучший на рынке» проскочило в статью — а вычеркнуть его на проверке стоило один тап", "The empty “best on the market” slipped into the article, and crossing it out at the check would have cost one tap")
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (struck) primary else error,
        minLines = 3,
        maxLines = 3,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { struck = !struck }, enabled = phase == 2, modifier = Modifier.weight(1f)) {
            Text(if (struck) tr("Вернуть", "Restore") else tr("Вычеркнуть", "Cross out"))
        }
        Button(
            onClick = {
                if (phase == 4) {
                    phase = 0
                    struck = false
                } else {
                    phase++
                }
            },
            enabled = phase != 3,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                when (phase) {
                    0 -> tr("Запустить", "Start")
                    3 -> tr("Пишу…", "Writing…")
                    4 -> tr("Сначала", "Start over")
                    else -> tr("Дальше", "Next")
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouterPart() {
    var picked by rememberSaveable { mutableIntStateOf(-1) }
    var runs by rememberSaveable { mutableIntStateOf(0) }
    // 0 — the router is reading, 1 — the label is set, 2 — the request is in its branch.
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(runs) {
        phase = 0
        if (picked < 0) return@LaunchedEffect
        delay(600)
        phase = 1
        delay(700)
        phase = 2
    }
    val ask = routerAsks().getOrNull(picked)
    val primary = MaterialTheme.colorScheme.primary

    Text(tr("Входящие — выбери, что придёт:", "Incoming requests: pick what arrives:"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        routerAsks().forEachIndexed { index, item ->
            FilterChip(selected = picked == index, onClick = { picked = index; runs++ }, label = { Text(item.text) })
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(2.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp),
    ) {
        Text(
            text = if (ask == null) tr("Роутер: жду запрос", "Router: waiting for a request") else tr("Роутер: «${ask.text}»", "Router: “${ask.text}”"),
            style = MaterialTheme.typography.bodyMedium,
            color = SceneInk,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        AnimatedVisibility(visible = ask != null && phase >= 1, enter = scaleIn(initialScale = 1.8f) + fadeIn()) {
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(2.dp, primary, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(ask?.tag.orEmpty(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = primary)
            }
        }
    }
    routerLanes().forEachIndexed { index, (name, cost) ->
        val lit = ask != null && phase >= 2 && ask.lane == index
        val background by animateColorAsState(if (lit) ScenePalette[2] else Color.White, tween(300), label = "lane")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(background)
                .padding(horizontal = 12.dp),
        ) {
            Column(modifier = Modifier.width(128.dp)) {
                Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = SceneInk, maxLines = 1)
                Text(cost, style = MaterialTheme.typography.labelSmall, color = SceneInk.copy(alpha = 0.6f), maxLines = 1)
            }
            AnimatedVisibility(visible = lit, enter = fadeIn(tween(300)), modifier = Modifier.weight(1f)) {
                Text("→ ${ask?.text.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = SceneInk, maxLines = 2)
            }
        }
    }
    Text(
        text = if (ask != null && phase >= 2) ask.result else " ",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = primary,
        minLines = 3,
        maxLines = 3,
    )
}
