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
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.delay

/** A part of the agent: the label on the chip, what appears in the card and what is missing without it. */
private class AgentPart(val chip: String, val slot: String, val missing: String)

// The same parts as the builder steps in the lesson. "Check" is played by the test button. The hook is as it
// really gets built in the builder: a hook's condition is only "always" or "a tool from the list"; there is
// no "before any write" hook there.
// A getter, not a val: the text is picked in the interface language when it is shown, not once at class load.
private val PARTS: List<AgentPart>
    get() = listOf(
        AgentPart(
            tr("Личность", "Identity"),
            tr("Домовёнок · своя память", "Brownie · own memory"),
            tr("без личности агент безымянный и без своего отсека памяти", "without an identity the agent has no name and no memory compartment of its own"),
        ),
        AgentPart(
            tr("Инструкции", "Instructions"),
            tr("«Коротко и по делу»", "“Short and to the point”"),
            tr("без инструкций агент болтает не по делу", "without instructions the agent rambles off topic"),
        ),
        AgentPart(
            tr("Инструменты", "Tools"),
            tr("напоминания, календарь", "reminders, calendar"),
            tr("без инструментов агент только говорит, а сделать ничего не может", "without tools the agent can only talk and cannot do anything"),
        ),
        AgentPart(
            tr("Режим", "Mode"),
            tr("обычный ответ", "plain reply"),
            tr("режим не выбран — сработает обычный ответ по умолчанию", "no mode is chosen, so the plain default reply is used"),
        ),
        AgentPart(
            tr("Хуки", "Hooks"),
            tr("перед «Напоминаниями» — спросить", "before “Reminders”: ask first"),
            tr("без хуков — только правила по умолчанию: запись идёт без вопроса", "without hooks only the default rules apply: a write goes through without asking"),
        ),
    )
private const val PERSONA = 0
private const val INSTRUCTIONS = 1
private const val TOOLS = 2
private const val MODE = 3
private const val HOOKS = 4
private val TEST_REQUEST: String get() = tr("Напомни мне завтра купить хлеб", "Remind me to buy bread tomorrow")
private val DoneGreen = Color(0xFF2E7D32)

private fun Int.hasPart(index: Int) = (this and (1 shl index)) != 0

private fun replyFor(parts: Int): String {
    val tools = parts.hasPart(TOOLS)
    val instructions = parts.hasPart(INSTRUCTIONS)
    return when {
        tools && instructions -> tr("Поставил напоминание: завтра в 9:00 — купить хлеб.", "Reminder set: tomorrow at 9:00, buy bread.")
        tools -> tr(
            "Поставил напоминание! Кстати, рассказать, какой хлеб полезнее? Могу и рецепт дать…",
            "Reminder set! By the way, shall I tell you which bread is healthier? I can give you a recipe too…",
        )
        instructions -> tr("Хорошо, не забудь завтра купить хлеб!", "Okay, don't forget to buy bread tomorrow!")
        else -> tr(
            "Хлеб — это важно! Кстати, могу рассказать, какой хлеб полезнее, или дать рецепт…",
            "Bread is important! By the way, I can tell you which bread is healthier, or give you a recipe…",
        )
    }
}

/**
 * "Build your agent": the user taps parts — identity, instructions, tools, mode, hooks — and the agent's card
 * assembles before their eyes. The test "remind me to buy bread tomorrow" can be run at any stage: without
 * tools the agent only promises in words, with them it sets the reminder (writing goes without asking by
 * default), with a hook on "Reminders" it also asks for permission. An agent is the sum of the parts you have
 * already gone through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentAssemblerScene() {
    var parts by rememberSaveable { mutableIntStateOf(0) }
    // Which parts the last test ran with; -1 — not run yet.
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
        Text(tr("Тапай части — агент соберётся в карточке", "Tap the parts: the agent assembles in the card"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                    tested < 0 -> tr("Проверить агента", "Test the agent")
                    stale -> tr("Части изменились — проверить", "Parts changed: test again")
                    else -> tr("Проверить ещё раз", "Test again")
                },
                maxLines = 1,
            )
        }
        Text(
            text = if (firstMissing == null) {
                tr(
                    "Все пять частей на месте — это и есть агент: сумма всего, что ты прошёл в курсе",
                    "All five parts are in place. That is the agent: the sum of everything you have learned in the course",
                )
            } else {
                tr("Не хватает", "Missing") + ": ${PARTS[firstMissing].chip.lowercase()} — ${PARTS[firstMissing].missing}"
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
                text = if (persona) tr("Домовёнок", "Brownie") else tr("Безымянный агент", "Unnamed agent"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = SceneInk,
                modifier = Modifier.weight(1f),
            )
            Text(tr("частей", "parts") + ": ${parts.countOneBits()}/${PARTS.size}", style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f))
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
                        text = if (filled) part.slot else tr("пусто", "empty"),
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

/** The test result: the agent's answer and what it actually did. The lines are reserved — the panel does not jump. */
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
            // The parts changed after the test — the old result is dimmed until you run it again.
            .alpha(if (stale && !thinking) 0.5f else 1f),
    ) {
        Text(
            text = tr("Тест", "Test") + ": “$TEST_REQUEST”",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        val who = if (ran && tested.hasPart(PERSONA)) tr("Домовёнок", "Brownie") else tr("Агент", "Agent")
        val mode = if (ran && tested.hasPart(MODE)) tr("обычный ответ", "plain reply") else tr("режим по умолчанию", "default mode")
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(10.dp)) {
            Text(
                text = when {
                    thinking -> tr("Агент думает…", "The agent is thinking…")
                    !ran -> tr("Агента можно проверять на любом этапе сборки — даже пустого", "You can test the agent at any stage of the build, even an empty one")
                    else -> "$who ($mode): ${replyFor(tested)}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SceneInk,
                minLines = 3,
            )
        }
        // What the agent did for real: a reminder was set — or there were only words.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            if (ran) {
                if (tools) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(ScenePalette[2]).padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(Icons.Default.Alarm, contentDescription = null, tint = SceneInk, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("Завтра, 9:00 — купить хлеб", "Tomorrow, 9:00: buy bread"), style = MaterialTheme.typography.labelMedium, color = SceneInk, maxLines = 1)
                    }
                } else {
                    Text(tr("Только слова: напоминания нет — нечем поставить", "Only words: no reminder, because there is nothing to set it with"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = error, maxLines = 2)
                }
            }
        }
        Text(
            text = when {
                !ran -> " "
                hooks && tools -> tr(
                    "Хук «перед «Напоминаниями» — спросить»: агент спросил «Ставлю на 9:00?» и поставил после «Разрешить»",
                    "Hook “before ‘Reminders’: ask first”: the agent asked “Set it for 9:00?” and set it after “Allow”",
                )
                hooks -> tr("Хук ждёт: охранять нечего — у агента нет «Напоминаний»", "The hook waits: there is nothing to guard, because the agent has no “Reminders”")
                tools -> tr(
                    "Хуков нет: напоминание — это запись, она идёт без вопроса. Спросит само только удаление или отправка",
                    "No hooks: a reminder is a write, so it goes through without a question. Only deleting or sending asks on its own",
                )
                else -> " "
            },
            style = MaterialTheme.typography.bodySmall,
            minLines = 2,
        )
    }
}
