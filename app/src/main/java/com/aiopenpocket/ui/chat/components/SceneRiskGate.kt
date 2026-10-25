package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Один вызов: инструмент, действие, класс риска (индекс в [RISKS]) и что вышло при «Разрешить»/«Отклонить». */
private class GateCall(val tool: String, val action: String, val risk: Int, val allowed: String, val denied: String)

// Четыре класса из урока. У агента в сцене правило-хук «спрашивай перед всем, кроме чтения»,
// поэтому всё, что не «только чтение», ждёт твоего «ок».
private val RISKS = listOf("только чтение", "изменяет данные", "покидает устройство", "может удалить необратимо")

// «Напоминания» встречаются дважды: тот же инструмент, но риск у вызовов разный.
private val CALLS = listOf(
    GateCall("заметки", "прочитать «Покупки»", 0, "", ""),
    GateCall("напоминания", "показать список", 0, "", ""),
    GateCall("будильник", "поставить на 7:00", 1, "будильник поставлен", "будильник не тронут"),
    GateCall("почта", "письмо Ане", 2, "письмо ушло", "письмо не отправлено"),
    GateCall("напоминания", "отменить все", 3, "напоминания отменены", "напоминания целы"),
)

/**
 * «Полоса подтверждения»: вызовы агента приходят по одному и сами раскладываются по дорожкам —
 * читающие проходят молча, остальные замирают и ждут твоего «Разрешить» или «Отклонить». Видно,
 * что риск считают по каждому вызову: «напоминания» молча показывают список, но «отменить все» спросит.
 */
@Composable
internal fun RiskGateScene() {
    var arrived by rememberSaveable { mutableIntStateOf(0) }
    var allowedMask by rememberSaveable { mutableIntStateOf(0) }
    var deniedMask by rememberSaveable { mutableIntStateOf(0) }
    val current = CALLS.getOrNull(arrived - 1)
    val currentBit = 1 shl (arrived - 1).coerceAtLeast(0)
    val pending = current != null && current.risk > 0 && ((allowedMask or deniedMask) and currentBit) == 0
    val finished = arrived == CALLS.size && !pending
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Агент делает вызовы по одному. Куда попадёт каждый?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(200.dp)) {
            listOf("Молча", "Спросит твоё «ок»").forEachIndexed { lane, title ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.45f))
                        .padding(6.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp))
                    CALLS.forEachIndexed { index, call ->
                        if (index < arrived && (call.risk > 0) == (lane == 1)) {
                            val bit = 1 shl index
                            val mark = when {
                                call.risk == 0 -> "✓"
                                (allowedMask and bit) != 0 -> "✓"
                                (deniedMask and bit) != 0 -> "✕"
                                else -> "…"
                            }
                            CallChip(call, mark, newest = index == arrived - 1)
                        }
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (finished) primary.copy(alpha = 0.14f) else Color.White)
                .padding(12.dp),
        ) {
            Text(
                text = when {
                    current == null -> "Жми «Следующий вызов». У агента правило: спрашивать перед всем, кроме чтения."
                    finished -> "«Напоминания»: список — молча, «отменить все» — с твоим «ок». Риск считают по каждому вызову."
                    current.risk == 0 -> "${current.tool}: ${current.action} — «${RISKS[0]}», нечего испортить. Выполнено молча."
                    pending -> "${current.tool}: ${current.action} — «${RISKS[current.risk]}». Ход замер и ждёт тебя."
                    (allowedMask and currentBit) != 0 -> "Разрешено — ${current.allowed}."
                    else -> "Запрещено — ${current.denied}. Без «ок» агент действие не трогает."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (finished) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 3,
            )
        }
        // Полоса подтверждения — как внизу чата. Место под неё занято всегда, чтобы кнопка ниже не прыгала.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (pending) {
                OutlinedButton(onClick = { deniedMask = deniedMask or currentBit }, modifier = Modifier.weight(1f)) { Text("Отклонить", color = error) }
                Button(onClick = { allowedMask = allowedMask or currentBit }, modifier = Modifier.weight(1f)) { Text("Разрешить") }
            } else {
                Text(
                    text = "Здесь появится полоса подтверждения",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Button(
            onClick = {
                if (finished) {
                    arrived = 0; allowedMask = 0; deniedMask = 0
                } else {
                    arrived++
                }
            },
            enabled = !pending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (finished) "Сначала" else "Следующий вызов")
        }
    }
}

@Composable
private fun CallChip(call: GateCall, mark: String, newest: Boolean) {
    val fill = when (call.risk) {
        0 -> ScenePalette[2]
        1 -> ScenePalette[4]
        2 -> ScenePalette[1]
        else -> MaterialTheme.colorScheme.error.copy(alpha = 0.3f).compositeOver(Color.White)
    }
    // Появляется въездом сверху — вызов «прилетает» в свою дорожку.
    val appear = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(visibleState = appear, enter = fadeIn() + slideInVertically { -it }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(fill)
                .border(2.dp, if (newest) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(horizontal = 8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(call.tool, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SceneInk, maxLines = 1)
                Text(call.action, style = MaterialTheme.typography.labelSmall, color = SceneInk, maxLines = 1)
            }
            Text(mark, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = SceneInk)
        }
    }
}
