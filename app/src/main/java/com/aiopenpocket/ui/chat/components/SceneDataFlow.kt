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
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.delay

/** What lives on the phone, whether it goes to the provider with the question and why — exactly as the lesson says. */
private class DataItem(val label: String, val leaves: Boolean, val why: String)

// A getter, not a stored list: tr() has to run when the scene is drawn, so a language switch is seen at once.
private val ITEMS: List<DataItem>
    get() = listOf(
        DataItem(tr("Твой вопрос", "Your question"), true, tr("Уходит: модель отвечает именно на него.", "Goes: the model answers exactly this.")),
        DataItem(
            tr("История чата", "Chat history"), true,
            tr("Уходит — но только то, что влезает в окно контекста.", "Goes, but only what fits in the context window."),
        ),
        DataItem(tr("Профиль", "Profile"), true, tr("Уходит в препромпт, чтобы ответ был про тебя.", "Goes into the pre-prompt, so the answer is about you.")),
        DataItem(
            tr("Факт: кот Барсик", "Fact: cat Barsik"), true,
            tr("Уходит: приложение выбрало его — он про этот вопрос.", "Goes: the app picked it, because it's about this question."),
        ),
        DataItem(
            tr("Факт: отпуск в мае", "Fact: vacation in May"), false,
            tr("Остаётся: к подарку коту не относится, в запрос не выбран.", "Stays: it has nothing to do with a gift for a cat, so it isn't in the request."),
        ),
        DataItem(
            tr("Вся база и файлы", "The whole database and files"), false,
            tr("Остаётся: целиком база не отправляется никогда.", "Stays: the whole database is never sent."),
        ),
    )

private const val CHIP_HEIGHT = 40
private const val CHIP_GAP = 6

// Attempts on a failure: 0 — waiting, 1 — trying, 2 — failed, 3 — answered.
private const val IDLE = 0
private const val TRYING = 1
private const val FAILED = 2
private const val ANSWERED = 3

/**
 * "What leaves the phone": the user taps data, and each piece either goes to the model's provider or stays at
 * home under a lock — with an explanation why. Below is a failure: a model went down — the app tries the next
 * model of the same provider; the network dropped — an honest error instead of an endless wait.
 */
@Composable
internal fun DataFlowScene() {
    var sorted by rememberSaveable { mutableIntStateOf(0) } // bits: which data have already been tapped
    var last by rememberSaveable { mutableIntStateOf(-1) }
    val all = (1 shl ITEMS.size) - 1
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Ты спросил: «что подарить коту?» Тапай данные — что уйдёт с вопросом?", "You asked: “what should I give my cat?” Tap the data: what goes with the question?"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 8.dp
            val half = (maxWidth - gap) / 2
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row {
                    Text(tr("Телефон", "Phone"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(half))
                    Spacer(Modifier.width(gap))
                    // The request goes straight to Meta, so Meta is the only party besides the phone that sees it.
                    Text("Meta", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = primary, modifier = Modifier.width(half))
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
                    sorted == all -> tr("Провайдер видит выбранный для ответа контекст, а не всю базу.", "The provider sees the context picked for the answer, not the whole database.")
                    last >= 0 -> ITEMS[last].why
                    else -> tr("Данные живут у тебя. Что из них поедет с вопросом?", "Your data lives with you. Which of it goes with the question?")
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (sorted == all) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 2,
            )
        }
        TextButton(onClick = { sorted = 0; last = -1 }, enabled = sorted != 0, modifier = Modifier.align(Alignment.End)) { Text(tr("Сначала", "Start over")) }
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
    // What stays at home "twitches" in place: it would like to leave, but the lock does not let it.
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
            Icon(Icons.Filled.Lock, contentDescription = tr("остаётся на телефоне", "stays on the phone"), tint = SceneInk, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(item.label, style = MaterialTheme.typography.labelMedium, color = SceneInk, maxLines = 2)
    }
}

/** A request failure: whichever breakage the learner picked, the attempts go through the models of the chosen provider accordingly. */
@Composable
private fun NetworkFailure() {
    var mode by rememberSaveable { mutableIntStateOf(-1) } // 0 — a model went down, 1 — the network dropped
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
        Text(tr("А если запрос сорвался?", "And if the request fails?"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(tr("Модель упала", "A model went down"), tr("Сеть пропала", "The network dropped")).forEachIndexed { index, label ->
                FilterChip(selected = mode == index, onClick = { mode = index; run++ }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            attempts.forEachIndexed { index, state -> AttemptBox(tr("Модель ${index + 1}", "Model ${index + 1}"), state, networkDown = mode == 1, modifier = Modifier.weight(1f)) }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp)) {
            Text(
                text = when {
                    mode < 0 -> tr("Выбери поломку — посмотрим, что сделает приложение.", "Pick a failure and see what the app does.")
                    done == 0 -> tr("Пробуем…", "Trying…")
                    mode == 0 -> tr("Ответ пришёл от запасной модели Muse. К другой компании запрос не ушёл.", "The answer came from a backup Muse model. The request never went to another company.")
                    else -> tr("Без сети запасные модели не помогут: честная ошибка и «Повторить» — ход пойдёт с нуля.", "With no network, backup models can't help: an honest error and “Retry” — the turn starts from scratch.")
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
                TRYING -> tr("пробуем…", "trying…")
                FAILED -> if (networkDown) tr("✕ нет сети", "✕ no network") else tr("✕ лимит", "✕ limit")
                ANSWERED -> tr("✓ ответ", "✓ answer")
                else -> "—"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (state == FAILED) MaterialTheme.colorScheme.error else SceneInk.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )
    }
}
