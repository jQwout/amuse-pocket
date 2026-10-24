package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

// События одного хода — те, что называет урок, в порядке, в котором их проходит ход с инструментом.
private val EVENTS = listOf("Перед ходом", "Перед инструментом", "После инструмента", "После хода", "Завершение")

// Правила: два на одно событие (разрешить и запретить «почту») и одно, которое только пишет в журнал.
private val RULES = listOf("Перед «почтой» → разрешить", "Перед «почтой» → запретить", "После хода → в журнал")
private const val ALLOW = 0
private const val DENY = 1
private const val LOG = 2

private const val ROW = 46
private const val RAIL = 24
private const val RING = 22
private const val STEP_MS = 550

private fun Int.on(rule: Int) = (this and (1 shl rule)) != 0

/** Что видно на событии [index] при включённых правилах — движок детерминирован: те же правила, тот же итог. */
private fun outcome(index: Int, allow: Boolean, deny: Boolean, log: Boolean): String = when (index) {
    0 -> "агент начал думать"
    1 -> when {
        allow && deny -> "«разрешить» + «запретить» → запрет"
        deny -> "«запретить» → вызов заблокирован"
        allow -> "«разрешить» → вызов идёт дальше"
        else -> "почта: письмо Ане → идёт дальше"
    }
    2 -> if (deny) "пропущено — вызова не было" else "письмо ушло — после твоего «ок»"
    3 -> if (log) "агент ответил · запись в журнал" else "агент ответил"
    else -> "ход закрыт"
}

/**
 * «Хуки на ходу»: пользователь включает правила и прогоняет один ход — метка бежит по событиям,
 * и на своём событии каждое правило срабатывает у всех на виду: запрет блокирует письмо, журнал
 * получает запись. Включи «разрешить» и «запретить» разом — победит запрет.
 */
@Composable
internal fun HookTimelineScene() {
    var rules by rememberSaveable { mutableIntStateOf(0) }
    var run by rememberSaveable { mutableIntStateOf(0) } // 0 — ещё не прогоняли с этими правилами
    val marker = remember { Animatable(-1f) }
    val last = (EVENTS.size - 1).toFloat()
    LaunchedEffect(run) {
        if (run == 0) {
            marker.snapTo(-1f)
            return@LaunchedEffect
        }
        marker.snapTo(0f)
        marker.animateTo(last, tween(EVENTS.size * STEP_MS, easing = LinearEasing))
    }
    val allow = rules.on(ALLOW)
    val deny = rules.on(DENY)
    val log = rules.on(LOG)
    val running = marker.value >= 0f && marker.value < last
    val done = marker.value >= last
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Задача: «напиши Ане, что опоздаю на 10 минут». Повесь правила и прогони ход.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        RULES.forEachIndexed { index, rule ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (rules.on(index)) ScenePalette[if (index == DENY) 1 else 2] else Color.White)
                    .padding(horizontal = 12.dp),
            ) {
                Text(rule, style = MaterialTheme.typography.bodyMedium, color = SceneInk, maxLines = 1, modifier = Modifier.weight(1f))
                // Правило меняется — прошлый прогон уже не про него: метка возвращается на старт.
                Switch(checked = rules.on(index), onCheckedChange = { rules = rules xor (1 shl index); run = 0 }, enabled = !running)
            }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.45f)).padding(horizontal = 8.dp)) {
            Column {
                EVENTS.forEachIndexed { index, name ->
                    val reached = marker.value >= index - 0.05f
                    val blocked = deny && (index == 1 || index == 2)
                    val shown by animateFloatAsState(if (reached) 1f else 0f, tween(250), label = "hookOutcome")
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(ROW.dp)) {
                        Box(Modifier.width(RAIL.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Box(Modifier.width(2.dp).fillMaxHeight().background(primary.copy(alpha = 0.25f)))
                            Box(Modifier.size(12.dp).clip(CircleShape).background(if (reached) primary else Color.White).border(2.dp, primary.copy(alpha = 0.5f), CircleShape))
                        }
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = SceneInk)
                            Text(
                                text = outcome(index, allow, deny, log),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (blocked) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (blocked) error else SceneInk.copy(alpha = 0.75f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.alpha(shown),
                            )
                        }
                    }
                }
            }
            // Метка хода: кольцо едет по рельсу от события к событию.
            if (marker.value >= 0f) {
                Box(
                    Modifier
                        .offset { IntOffset(((RAIL - RING) / 2).dp.roundToPx(), (marker.value * ROW + (ROW - RING) / 2f).dp.roundToPx()) }
                        .size(RING.dp)
                        .border(3.dp, primary, CircleShape),
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (done) primary.copy(alpha = 0.14f) else Color.White)
                .padding(12.dp),
        ) {
            Text(
                text = when {
                    !done && running -> "Ход идёт по событиям…"
                    !done -> "Включи правила и жми «Прогнать ход»."
                    allow && deny -> "Письмо не ушло: один «запретить» перевешивает любое «разрешить»."
                    deny -> "Письмо не ушло: правило на «перед инструментом» остановило вызов."
                    allow -> "«Разрешить» пропустило вызов. Теперь включи и «запретить»."
                    else -> "Правил нет — ход прошёл как обычно. Повесь правило и прогони снова."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 2,
            )
        }
        Button(onClick = { run++ }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (done) "Прогнать ещё раз" else "Прогнать ход")
        }
    }
}
