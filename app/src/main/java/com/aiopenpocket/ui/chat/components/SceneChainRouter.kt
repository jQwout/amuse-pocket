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
import kotlinx.coroutines.delay

// Цепочка из урока: тезисы → твоя проверка → текст по тезисам.
private val CHAIN_STATIONS = listOf(
    "Шаг 1 · модель" to "«составь тезисы статьи про наш продукт»",
    "Проверка · ты" to "прочитай тезисы и вычеркни лишнее",
    "Шаг 2 · модель" to "«разверни эти тезисы в текст»",
)
private const val STATION_H = 100
private const val STATION_GAP = 10
private const val PACKET_H = 40

/** Запросы, метка роутера и ветка, куда он их отправит. Ветки — те, что в уроке: по сложности и по теме. */
private class RoutedAsk(val text: String, val tag: String, val lane: Int, val result: String)

private val ROUTER_LANES = listOf("Быстрая модель" to "дёшево", "Ветка «доставка»" to "свой промпт", "Большая модель" to "дороже, умнее")
private val ROUTER_ASKS = listOf(
    RoutedAsk("Сколько будет 2+2?", "простое", 0, "Незачем гонять дорогую модель на «2+2» — сортировка экономит деньги и секунды"),
    RoutedAsk("Где мой заказ?", "доставка", 1, "Вопрос про доставку — в свою ветку со своим промптом: точнее, чем один промпт на всё"),
    RoutedAsk("Объясните пункт 4.2 договора", "сложное", 2, "Сложное — к большой модели. А внутри ветки своя цепочка: найти пункт → объяснить"),
)

/**
 * «Цепочка и роутинг» на двух вкладках. В цепочке пользователь жмёт «Дальше», и один и тот же листок
 * съезжает со станции на станцию: выход шага становится входом следующего, а на проверке можно
 * вычеркнуть лишний тезис. В роутинге выбираешь входящий запрос — роутер ставит метку и шлёт его в свою ветку.
 */
@Composable
internal fun ChainRouterScene() {
    var routing by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !routing, onClick = { routing = false }, label = { Text("Цепочка") })
            FilterChip(selected = routing, onClick = { routing = true }, label = { Text("Роутинг") })
        }
        if (routing) RouterPart() else ChainPart()
    }
}

@Composable
private fun ChainPart() {
    // 0 — ждём, 1 — тезисы готовы, 2 — на проверке, 3 — дошли до шага 2, 4 — текст готов.
    var phase by rememberSaveable { mutableIntStateOf(0) }
    var struck by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(phase) {
        if (phase == 3) {
            delay(900) // листок сначала доезжает как вход — и только потом превращается в текст
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
            append(if (struck) "Статья: что умеет продукт и кому он нужен — без пустых обещаний" else "Статья: что умеет продукт, кому он нужен и почему он «лучший на рынке»")
        }
    } else {
        buildAnnotatedString {
            append("Тезисы: 1) что умеет продукт 2) кому он нужен ")
            // Сомнительный тезис помечен красным: его и стоит поймать на проверке.
            withStyle(SpanStyle(color = error, textDecoration = if (struck) TextDecoration.LineThrough else null)) {
                append("3) «лучший на рынке»")
            }
        }
    }

    Text("Задача: «напиши статью про наш продукт» — по шагам", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Box(modifier = Modifier.fillMaxWidth().height((3 * STATION_H + 2 * STATION_GAP).dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(STATION_GAP.dp)) {
            CHAIN_STATIONS.forEachIndexed { index, (role, task) ->
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
        // Один и тот же листок едет вниз: что вышло из станции, то и вошло в следующую.
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
            struck -> "Промах поймали на проверке — до того, как на него потратился шаг 2. Каждый шаг маленький и проверяемый"
            else -> "Пустое «лучший на рынке» проскочило в статью — а вычеркнуть его на проверке стоило один тап"
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (struck) primary else error,
        minLines = 3,
        maxLines = 3,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { struck = !struck }, enabled = phase == 2, modifier = Modifier.weight(1f)) {
            Text(if (struck) "Вернуть" else "Вычеркнуть")
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
                    0 -> "Запустить"
                    3 -> "Пишу…"
                    4 -> "Сначала"
                    else -> "Дальше"
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
    // 0 — роутер читает, 1 — метка поставлена, 2 — запрос в своей ветке.
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(runs) {
        phase = 0
        if (picked < 0) return@LaunchedEffect
        delay(600)
        phase = 1
        delay(700)
        phase = 2
    }
    val ask = ROUTER_ASKS.getOrNull(picked)
    val primary = MaterialTheme.colorScheme.primary

    Text("Входящие — выбери, что придёт:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ROUTER_ASKS.forEachIndexed { index, item ->
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
            text = if (ask == null) "Роутер: жду запрос" else "Роутер: «${ask.text}»",
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
    ROUTER_LANES.forEachIndexed { index, (name, cost) ->
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
