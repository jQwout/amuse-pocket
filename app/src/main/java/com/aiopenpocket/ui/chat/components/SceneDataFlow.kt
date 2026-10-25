package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Что лежит в телефоне, уйдёт ли это провайдеру вместе с вопросом и почему — ровно по тексту урока. */
private class DataItem(val label: String, val leaves: Boolean, val why: String)

private val ITEMS = listOf(
    DataItem("Твой вопрос", true, "Уходит: модель отвечает именно на него."),
    DataItem("История чата", true, "Уходит — но только то, что влезает в окно контекста."),
    DataItem("Профиль", true, "Уходит в препромпт, чтобы ответ был про тебя."),
    DataItem("Факт: кот Барсик", true, "Уходит: приложение выбрало его — он про этот вопрос."),
    DataItem("Факт: отпуск в мае", false, "Остаётся: к подарку коту не относится, в запрос не выбран."),
    DataItem("Вся база и файлы", false, "Остаётся: целиком база не отправляется никогда."),
)

private const val CHIP_HEIGHT = 40
private const val CHIP_GAP = 6

// Попытки при сбое: 0 — ждёт, 1 — пробуем, 2 — упала, 3 — ответила.
private const val IDLE = 0
private const val TRYING = 1
private const val FAILED = 2
private const val ANSWERED = 3

/**
 * «Что уходит из телефона»: пользователь тапает данные, и каждое либо уезжает к провайдеру модели,
 * либо остаётся дома под замком — с объяснением почему. Ниже — сбой: упала модель — приложение
 * пробует следующую модель того же провайдера; пропала сеть — честная ошибка вместо вечного ожидания.
 */
@Composable
internal fun DataFlowScene() {
    var sorted by rememberSaveable { mutableIntStateOf(0) } // биты: какие данные уже тапнули
    var last by rememberSaveable { mutableIntStateOf(-1) }
    val all = (1 shl ITEMS.size) - 1
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ты спросил: «что подарить коту?» Тапай данные — что уйдёт с вопросом?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 8.dp
            val half = (maxWidth - gap) / 2
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text("Телефон", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(half))
                    Spacer(Modifier.width(gap))
                    // Запрос видят двое: посредник (OpenRouter) и сама Meta — урок называет обоих.
                    Text("OpenRouter и Meta", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = primary, modifier = Modifier.width(half))
                }
                Box(Modifier.fillMaxWidth().height(((CHIP_HEIGHT + CHIP_GAP) * ITEMS.size + 10).dp)) {
                    Row(Modifier.fillMaxHeight()) {
                        Box(Modifier.width(half).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.45f)))
                        Spacer(Modifier.width(gap))
                        Box(Modifier.width(half).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(primary.copy(alpha = 0.10f)))
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(CHIP_GAP.dp), modifier = Modifier.padding(6.dp)) {
                        ITEMS.forEachIndexed { index, item ->
                            DataChip(
                                item = item,
                                sorted = (sorted and (1 shl index)) != 0,
                                width = half - 12.dp,
                                shift = half + gap,
                                onTap = { sorted = sorted or (1 shl index); last = index },
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (sorted == all) primary.copy(alpha = 0.14f) else Color.White).padding(12.dp)) {
            Text(
                text = when {
                    sorted == all -> "Провайдер видит выбранный для ответа контекст, а не всю базу."
                    last >= 0 -> ITEMS[last].why
                    else -> "Данные живут у тебя. Что из них поедет с вопросом?"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (sorted == all) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 2,
            )
        }
        TextButton(onClick = { sorted = 0; last = -1 }, enabled = sorted != 0, modifier = Modifier.align(Alignment.End)) { Text("Сначала") }
        NetworkFailure()
    }
}

@Composable
private fun DataChip(item: DataItem, sorted: Boolean, width: Dp, shift: Dp, onTap: () -> Unit) {
    val x by animateDpAsState(if (sorted && item.leaves) shift else 0.dp, tween(550), label = "chipTravel")
    val fill by animateColorAsState(
        when {
            !sorted -> Color.White
            item.leaves -> ScenePalette[0]
            else -> ScenePalette[2]
        },
        tween(300),
        label = "chipFill",
    )
    // Оставшееся дома «дёргается» на месте: хотело бы уехать, но замок не пускает.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(sorted) {
        if (sorted && !item.leaves) {
            shake.animateTo(0f, keyframes { durationMillis = 360; 8f at 60; -8f at 140; 5f at 220; -3f at 290 })
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .offset { IntOffset((x.toPx() + shake.value.dp.toPx()).toInt(), 0) }
            .width(width)
            .height(CHIP_HEIGHT.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .border(1.dp, SceneInk.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .clickable(enabled = !sorted, onClick = onTap)
            .padding(horizontal = 8.dp),
    ) {
        if (sorted && !item.leaves) {
            Icon(Icons.Filled.Lock, contentDescription = "остаётся на телефоне", tint = SceneInk, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = SceneInk, maxLines = 2)
    }
}

/** Сбой запроса: какую поломку выбрал ученик — так и проходят попытки по моделям выбранного провайдера. */
@Composable
private fun NetworkFailure() {
    var mode by rememberSaveable { mutableIntStateOf(-1) } // 0 — модель упала, 1 — сеть пропала
    var run by rememberSaveable { mutableIntStateOf(0) }
    val attempts = remember { mutableStateListOf(IDLE, IDLE, IDLE) }
    var done by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        if (mode < 0) return@LaunchedEffect
        done = 0
        for (i in attempts.indices) attempts[i] = IDLE
        for (i in attempts.indices) {
            attempts[i] = TRYING
            delay(500)
            val ok = mode == 0 && i == 1
            attempts[i] = if (ok) ANSWERED else FAILED
            if (ok) break
        }
        done = 1
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("А если запрос сорвался?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Модель упала", "Сеть пропала").forEachIndexed { index, label ->
                FilterChip(selected = mode == index, onClick = { mode = index; run++ }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            attempts.forEachIndexed { index, state -> AttemptBox("Модель ${index + 1}", state, networkDown = mode == 1, modifier = Modifier.weight(1f)) }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
            Text(
                text = when {
                    mode < 0 -> "Выбери поломку — посмотрим, что сделает приложение."
                    done == 0 -> "Пробуем…"
                    mode == 0 -> "Ответ пришёл от запасной модели Muse. К другой компании запрос не ушёл."
                    else -> "Без сети запасные модели не помогут: честная ошибка и «Повторить» — ход пойдёт с нуля."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (done == 1) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 3,
            )
        }
    }
}

@Composable
private fun AttemptBox(label: String, state: Int, networkDown: Boolean, modifier: Modifier) {
    val accent = when (state) {
        FAILED -> MaterialTheme.colorScheme.error
        ANSWERED, TRYING -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }
    val border by animateColorAsState(accent, tween(250), label = "attemptBorder")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .border(2.dp, border, RoundedCornerShape(10.dp)),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = SceneInk)
        Text(
            text = when (state) {
                TRYING -> "пробуем…"
                FAILED -> if (networkDown) "✕ нет сети" else "✕ лимит"
                ANSWERED -> "✓ ответ"
                else -> "—"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state == FAILED) MaterialTheme.colorScheme.error else SceneInk.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}
