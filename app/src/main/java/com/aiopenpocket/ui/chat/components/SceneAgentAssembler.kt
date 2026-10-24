package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Часть агента: подпись на чипе, что появляется в карточке и чего без неё не хватает. */
private class AgentPart(val chip: String, val slot: String, val missing: String)

// Те же части, что шаги конструктора в уроке. «Проверку» играет кнопка теста.
private val PARTS = listOf(
    AgentPart("Личность", "Домовёнок · своя память", "без личности агент безымянный и без своего отсека памяти"),
    AgentPart("Инструкции", "«Коротко и по делу»", "без инструкций агент болтает не по делу"),
    AgentPart("Инструменты", "напоминания, заметки", "без инструментов агент только говорит, а сделать ничего не может"),
    AgentPart("Режим", "обычный ответ", "режим не выбран — сработает обычный ответ по умолчанию"),
    AgentPart("Хуки", "перед записью — спросить", "без хуков правил нет — агент пишет, не спросив"),
)
private const val PERSONA = 0
private const val INSTRUCTIONS = 1
private const val TOOLS = 2
private const val MODE = 3
private const val HOOKS = 4
private const val TEST_REQUEST = "Напомни мне завтра купить хлеб"
private val DoneGreen = Color(0xFF2E7D32)

private fun Int.hasPart(index: Int) = (this and (1 shl index)) != 0

private fun replyFor(parts: Int): String {
    val tools = parts.hasPart(TOOLS)
    val instructions = parts.hasPart(INSTRUCTIONS)
    return when {
        tools && instructions -> "Поставил напоминание: завтра в 9:00 — купить хлеб."
        tools -> "Поставил напоминание! Кстати, рассказать, какой хлеб полезнее? Могу и рецепт дать…"
        instructions -> "Хорошо, не забудь завтра купить хлеб!"
        else -> "Хлеб — это важно! Кстати, могу рассказать, какой хлеб полезнее, или дать рецепт…"
    }
}

/**
 * «Собери своего агента»: пользователь тапает части — личность, инструкции, инструменты, режим, хуки —
 * и карточка агента собирается на глазах. Тест «напомни завтра купить хлеб» можно гонять на любом
 * этапе: без инструментов агент только обещает словами, с ними — ставит напоминание, с хуком — ещё и
 * спрашивает разрешения. Агент — это сумма частей, которые ты уже прошёл.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentAssemblerScene() {
    var parts by rememberSaveable { mutableIntStateOf(0) }
    // С какими частями прогнан последний тест; -1 — ещё не гоняли.
    var tested by rememberSaveable { mutableIntStateOf(-1) }
    var pending by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pending) {
        val snapshot = pending ?: return@LaunchedEffect
        delay(600)
        tested = snapshot
        pending = null
    }
    val firstMissing = PARTS.indices.firstOrNull { !parts.hasPart(it) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Тапай части — агент соберётся в карточке", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PARTS.forEachIndexed { index, part ->
                FilterChip(selected = parts.hasPart(index), onClick = { parts = parts xor (1 shl index) }, label = { Text(part.chip) })
            }
        }
        AgentCard(parts = parts)
        val stale = tested >= 0 && tested != parts
        TestPanel(tested = tested, thinking = pending != null, stale = stale)
        Button(onClick = { pending = parts }, enabled = pending == null, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    tested < 0 -> "Проверить агента"
                    stale -> "Части изменились — проверить"
                    else -> "Проверить ещё раз"
                },
                maxLines = 1,
            )
        }
        Text(
            text = if (firstMissing == null) {
                "Все пять частей на месте — это и есть агент: сумма всего, что ты прошёл в курсе"
            } else {
                "Не хватает: ${PARTS[firstMissing].chip.lowercase()} — ${PARTS[firstMissing].missing}"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (firstMissing == null) FontWeight.SemiBold else FontWeight.Normal,
            color = if (firstMissing == null) DoneGreen else MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 3,
        )
    }
}

@Composable
private fun AgentCard(parts: Int) {
    val persona = parts.hasPart(PERSONA)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (persona) Icons.Default.Face else Icons.Default.SmartToy,
                contentDescription = null,
                tint = if (persona) MaterialTheme.colorScheme.primary else SceneInk.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (persona) "Домовёнок" else "Безымянный агент",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = SceneInk,
                modifier = Modifier.weight(1f),
            )
            Text("частей: ${parts.countOneBits()}/${PARTS.size}", style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f))
        }
        PARTS.forEachIndexed { index, part ->
            val filled = parts.hasPart(index)
            val background by animateColorAsState(if (filled) ScenePalette[index] else Color.White, tween(250), label = "slot")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(part.chip, style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.7f), modifier = Modifier.width(92.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(background)
                        .border(1.dp, if (filled) Color.Transparent else SceneInk.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = if (filled) part.slot else "пусто",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (filled) SceneInk else SceneInk.copy(alpha = 0.35f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Результат теста: ответ агента и что он на самом деле сделал. Строки зарезервированы — панель не прыгает. */
@Composable
private fun TestPanel(tested: Int, thinking: Boolean, stale: Boolean) {
    val error = MaterialTheme.colorScheme.error
    val ran = tested >= 0 && !thinking
    val tools = ran && tested.hasPart(TOOLS)
    val hooks = ran && tested.hasPart(HOOKS)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
            .padding(10.dp)
            // Части поменяли после теста — старый результат приглушён, пока не прогонишь заново.
            .alpha(if (stale && !thinking) 0.5f else 1f),
    ) {
        Text(
            text = "Тест: «$TEST_REQUEST»",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        val who = if (ran && tested.hasPart(PERSONA)) "Домовёнок" else "Агент"
        val mode = if (ran && tested.hasPart(MODE)) "обычный ответ" else "режим по умолчанию"
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp)) {
            Text(
                text = when {
                    thinking -> "Агент думает…"
                    !ran -> "Агента можно проверять на любом этапе сборки — даже пустого"
                    else -> "$who ($mode): ${replyFor(tested)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SceneInk,
                minLines = 3,
            )
        }
        // Что агент сделал на деле: напоминание поставлено — или были только слова.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            if (ran) {
                if (tools) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ScenePalette[2]).padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = SceneInk, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Завтра, 9:00 — купить хлеб", style = MaterialTheme.typography.labelMedium, color = SceneInk, maxLines = 1)
                    }
                } else {
                    Text("Только слова: напоминания нет — нечем поставить", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = error, maxLines = 2)
                }
            }
        }
        Text(
            text = when {
                !ran -> " "
                hooks && tools -> "Хук «перед записью — спросить»: агент спросил «Ставлю на 9:00?» и записал после «да»"
                hooks -> "Хук ждёт: охранять нечего — у агента нет инструмента записи"
                tools -> "Хуков нет: записал сразу, ни о чём не спросив"
                else -> " "
            },
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
        )
    }
}
