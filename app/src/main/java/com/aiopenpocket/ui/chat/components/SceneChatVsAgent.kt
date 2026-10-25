package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Звено цепочки агента: что это и что оно делает в этой просьбе. */
private class AgentLink(val title: String, val role: String)

private val AGENT_LINKS = listOf(
    AgentLink("Модель", "решает"),
    AgentLink("Инструмент", "будильник"),
    AgentLink("Результат", "в телефоне"),
)

// Именно «будильник»: на «напомни» приложение ставит уведомление-напоминание, а не будильник, и сцена
// с панелью «Будильники» учила бы не тому, что ученик потом увидит в чате.
private const val REMINDER_REQUEST = "Поставь будильник на завтра, 9:00 — позвонить маме"

// Ход сцены: ничего → модель думает → инструмент работает → готово.
private const val STEP_IDLE = 0
private const val STEP_MODEL = 1
private const val STEP_TOOL = 2
private const val STEP_DONE = 3

/**
 * «Чат или агент»: одна и та же просьба уходит то чату, то агенту. Чат вежливо отвечает словами — и
 * панель «Будильники» остаётся пустой. Агент проводит просьбу по цепочке модель → инструмент → результат,
 * и будильник появляется на самом деле: чат говорит, агент действует.
 */
@Composable
internal fun ChatVsAgentScene() {
    var agent by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableIntStateOf(STEP_IDLE) }
    var triedChat by rememberSaveable { mutableStateOf(false) }
    var triedAgent by rememberSaveable { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }
    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        step = STEP_MODEL
        delay(700)
        if (agent) {
            step = STEP_TOOL
            delay(700)
        }
        step = STEP_DONE
        if (agent) triedAgent = true else triedChat = true
    }
    val done = step == STEP_DONE
    val alarmSet = agent && done
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Просьба: «$REMINDER_REQUEST»", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        ModeToggle(agent = agent) { value ->
            if (value != agent) {
                agent = value
                step = STEP_IDLE
                run = 0 // отменяет прогон, начатый в другом режиме
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            AGENT_LINKS.forEachIndexed { index, link ->
                if (index > 0) {
                    Text("→", color = SceneInk.copy(alpha = 0.5f), textAlign = TextAlign.Center, modifier = Modifier.width(16.dp))
                }
                // У чата есть только модель: инструмента и результата в мире у него нет вовсе.
                val available = agent || index == 0
                LinkPill(link = link, lit = available && step >= index + 1, available = available, modifier = Modifier.weight(1f))
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = when {
                    !done -> "Ответ появится здесь"
                    agent -> "Готово: будильник на 9:00 — «Позвонить маме»"
                    else -> "Хорошо! Поставь будильник на 9:00 и не забудь позвонить маме 🙂"
                },
                color = SceneInk.copy(alpha = if (done) 1f else 0.45f),
                style = MaterialTheme.typography.bodyMedium,
                minLines = 2,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(2.dp, if (alarmSet) primary else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("Будильники", style = MaterialTheme.typography.labelMedium, color = SceneInk, fontWeight = FontWeight.SemiBold)
            Crossfade(targetState = alarmSet, animationSpec = tween(300), label = "alarm") { set ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(32.dp)) {
                    if (set) {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("09:00, завтра — Позвонить маме", color = SceneInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("пусто", color = SceneInk.copy(alpha = 0.45f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Button(onClick = { run++ }, enabled = step == STEP_IDLE || done, modifier = Modifier.fillMaxWidth()) {
            Text(if (done) "Ещё раз" else if (agent) "Отправить агенту" else "Отправить чату")
        }
        Text(
            text = when {
                done && triedChat && triedAgent -> "Чат говорит, агент действует — через инструменты"
                done && agent -> "Модель решила, инструмент сделал: будильник стоит на самом деле"
                done -> "Чат ответил словами — а будильника нет. Помнить придётся тебе"
                agent -> "Теперь та же просьба — агенту"
                else -> "Отправь просьбу чату, потом переключись на агента"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
        )
    }
}

@Composable
private fun ModeToggle(agent: Boolean, onSelect: (Boolean) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(20.dp)
    Row(modifier = Modifier.fillMaxWidth().height(40.dp).clip(shape).border(1.5.dp, primary, shape)) {
        listOf("Чат" to false, "Агент" to true).forEach { (label, value) ->
            val selected = agent == value
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (selected) primary else Color.White)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(value) }),
            ) {
                Text(
                    text = label,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else SceneInk,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LinkPill(link: AgentLink, lit: Boolean, available: Boolean, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val background by animateColorAsState(if (lit) primary else Color.White, tween(300), label = "linkBg")
    val ink = if (lit) MaterialTheme.colorScheme.onPrimary else SceneInk
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .height(52.dp)
            .alpha(if (available) 1f else 0.35f)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.5.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(horizontal = 4.dp),
    ) {
        Text(link.title, color = ink, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(link.role, color = ink.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
