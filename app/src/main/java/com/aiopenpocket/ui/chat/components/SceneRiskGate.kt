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
import com.aiopenpocket.i18n.tr

/** One call: the tool, the action, the risk class (an index in [risks]) and what came of "Allow"/"Decline". */
private class GateCall(
    private val toolRu: String, private val toolEn: String,
    private val actionRu: String, private val actionEn: String,
    val risk: Int,
    private val allowedRu: String = "", private val allowedEn: String = "",
    private val deniedRu: String = "", private val deniedEn: String = "",
) {
    // A ru/en pair picked at display time: CALLS is built once, and a tr() in its initializer would freeze the
    // language of the first draw.
    val tool: String get() = tr(toolRu, toolEn)
    val action: String get() = tr(actionRu, actionEn)
    val allowed: String get() = tr(allowedRu, allowedEn)
    val denied: String get() = tr(deniedRu, deniedEn)
}

// The four classes from the lesson. The scene plays the app's default rule, without hooks: reading and writing
// go silently, while "leaves the device" and "may delete irreversibly" wait for your "ok".
private fun risks() = listOf(
    tr("только чтение", "read only"),
    tr("изменяет данные", "changes data"),
    tr("покидает устройство", "leaves the device"),
    tr("может удалить необратимо", "can delete irreversibly"),
)

/** From this class ([risks]) on, a call waits for an "ok" — the same as by default in the app's chat. */
private const val ASK_FROM_RISK = 2

// "Reminders" appears twice: the same tool, but the calls have different risk. The tools and actions are real
// (the app has no "notes", and mail only opens a draft).
private val CALLS = listOf(
    GateCall("календарь", "calendar", "показать события", "show events", 0),
    GateCall("напоминания", "reminders", "показать список", "show the list", 0),
    GateCall("будильник", "alarm", "поставить на 7:00", "set for 7:00", 1),
    GateCall(
        "почта", "email", "черновик письма Ане", "draft an email to Anna", 2,
        allowedRu = "черновик открыт", allowedEn = "the draft is open",
        deniedRu = "черновик не открыт", deniedEn = "the draft stays closed",
    ),
    GateCall(
        "напоминания", "reminders", "отменить напоминание", "cancel a reminder", 3,
        allowedRu = "напоминание отменено", allowedEn = "the reminder is canceled",
        deniedRu = "напоминание цело", deniedEn = "the reminder is untouched",
    ),
)

/**
 * "The confirmation bar": the agent's calls arrive one at a time and sort themselves into lanes — reading and
 * writing pass silently, sending outside and deleting freeze and wait for your "Allow" or "Decline". It shows
 * that risk is assessed per call: "reminders" silently shows the list, but "cancel a reminder" will ask.
 */
@Composable
internal fun RiskGateScene() {
    var arrived by rememberSaveable { mutableIntStateOf(0) }
    var allowedMask by rememberSaveable { mutableIntStateOf(0) }
    var deniedMask by rememberSaveable { mutableIntStateOf(0) }
    val current = CALLS.getOrNull(arrived - 1)
    val currentBit = 1 shl (arrived - 1).coerceAtLeast(0)
    val pending = current != null && current.risk >= ASK_FROM_RISK && ((allowedMask or deniedMask) and currentBit) == 0
    val finished = arrived == CALLS.size && !pending
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Агент делает вызовы по одному. Какой спросит твоё «ок»?", "The agent makes calls one at a time. Which one will ask for your “OK”?"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(200.dp)) {
            listOf(tr("Молча", "Silently"), tr("Спросит твоё «ок»", "Asks for your “OK”")).forEachIndexed { lane, title ->
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
                        if (index < arrived && (call.risk >= ASK_FROM_RISK) == (lane == 1)) {
                            val bit = 1 shl index
                            val mark = when {
                                call.risk < ASK_FROM_RISK -> "✓"
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
                    current == null -> tr(
                        "Жми «Следующий вызов». Правило по умолчанию: читать и записывать — молча, отправлять и удалять — только с твоим «ок».",
                        "Tap “Next call.” The default rule: read and write silently, send and delete only with your “OK.”",
                    )
                    finished -> tr(
                        "«Напоминания»: список — молча, «отменить» — с твоим «ок». Риск считают по каждому вызову.",
                        "“Reminders”: the list runs silently, “cancel” asks for your “OK.” Risk is judged per call.",
                    )
                    current.risk == 0 -> tr(
                        "${current.tool}: ${current.action} — «${risks()[0]}», нечего испортить. Выполнено молча.",
                        "${current.tool}: ${current.action} — “${risks()[0]},” nothing to break. Done silently.",
                    )
                    current.risk < ASK_FROM_RISK -> tr(
                        "${current.tool}: ${current.action} — «${risks()[current.risk]}». Запись на устройстве идёт без вопроса.",
                        "${current.tool}: ${current.action} — “${risks()[current.risk]}.” Writing on the device goes without a question.",
                    )
                    pending -> tr(
                        "${current.tool}: ${current.action} — «${risks()[current.risk]}». Ход замер и ждёт тебя.",
                        "${current.tool}: ${current.action} — “${risks()[current.risk]}.” The turn froze and waits for you.",
                    )
                    (allowedMask and currentBit) != 0 -> tr("Разрешено — ${current.allowed}.", "Allowed: ${current.allowed}.")
                    else -> tr(
                        "Запрещено — ${current.denied}. Без «ок» агент действие не трогает.",
                        "Denied: ${current.denied}. Without an “OK” the agent doesn't touch the action.",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (finished) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 3,
            )
        }
        // The confirmation bar — as at the bottom of the chat. Its space is always taken so the button below does not jump.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            if (pending) {
                OutlinedButton(onClick = { deniedMask = deniedMask or currentBit }, modifier = Modifier.weight(1f)) { Text(tr("Отклонить", "Deny"), color = error) }
                Button(onClick = { allowedMask = allowedMask or currentBit }, modifier = Modifier.weight(1f)) { Text(tr("Разрешить", "Allow")) }
            } else {
                Text(
                    text = tr("Здесь появится полоса подтверждения", "The confirmation bar will appear here"),
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
            Text(if (finished) tr("Сначала", "Start over") else tr("Следующий вызов", "Next call"))
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
    // It appears by sliding in from the top — the call "flies" into its lane.
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
