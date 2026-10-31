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
import com.aiopenpocket.i18n.tr

// The events of one turn — the ones the lesson names, in the order a turn with a tool passes them.
// Getters, not vals: the text is picked in the interface language when it is shown, not once at class load.
private val EVENTS: List<String>
    get() = listOf(
        tr("Перед ходом", "Before turn"),
        tr("Перед инструментом", "Before tool"),
        tr("После инструмента", "After tool"),
        tr("После хода", "After turn"),
        tr("Завершение", "Completion"),
    )

// Rules: two on the same event (allow and deny "mail") and one that only writes to the log.
private val RULES: List<String>
    get() = listOf(
        tr("Перед «почтой» → разрешить", "Before “Email” → allow"),
        tr("Перед «почтой» → запретить", "Before “Email” → deny"),
        tr("После хода → в журнал", "After turn → write to log"),
    )
private const val ALLOW = 0
private const val DENY = 1
private const val LOG = 2

private const val ROW = 46
private const val RAIL = 24
private const val RING = 22
private const val STEP_MS = 550

private fun Int.on(rule: Int) = (this and (1 shl rule)) != 0

/**
 * What is visible at event [index] with the rules switched on — the engine is deterministic: the same rules,
 * the same result. Mail leaves the phone, so it asks for your "ok" even without hooks; "allow" does not cancel
 * that — the scene must not teach that "Allow" bypasses the confirmation.
 */
private fun outcome(index: Int, allow: Boolean, deny: Boolean, log: Boolean): String = when (index) {
    0 -> tr("агент начал думать", "the agent started thinking")
    1 -> when {
        allow && deny -> tr("«разрешить» + «запретить» → запрет", "“allow” + “deny” → deny")
        deny -> tr("«запретить» → вызов заблокирован", "“deny” → the call is blocked")
        allow -> tr("«разрешить» не обходит → ждём твоё «ок»", "“allow” doesn't bypass it → waiting for your “OK”")
        else -> tr("почта: черновик Ане → ждём твоё «ок»", "email: a draft to Ann → waiting for your “OK”")
    }
    2 -> if (deny) tr("пропущено — вызова не было", "skipped: there was no call") else tr("черновик открыт — после твоего «ок»", "draft opened, after your “OK”")
    3 -> if (log) tr("агент ответил · запись в журнал", "the agent replied · written to the log") else tr("агент ответил", "the agent replied")
    else -> tr("ход закрыт", "the turn is closed")
}

/**
 * "Hooks along the turn": the user switches on rules and runs one turn — a marker runs along the events, and at
 * its event each rule fires in plain sight: the ban blocks the email draft, the log gets an entry. Switch on
 * "allow" and "deny" at once — the ban wins.
 */
@Composable
internal fun HookTimelineScene() {
    var rules by rememberSaveable { mutableIntStateOf(0) }
    var run by rememberSaveable { mutableIntStateOf(0) } // 0 — not run with these rules yet
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
        Text(tr("Задача: «напиши Ане, что опоздаю на 10 минут». Повесь правила и прогони ход.", "Task: “write to Ann that I will be 10 minutes late.” Attach the rules and run the turn."), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                // A rule changes — the previous run is no longer about it: the marker goes back to the start.
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
            // The turn marker: a ring travels along the rail from event to event.
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
                    !done && running -> tr("Ход идёт по событиям…", "The turn is moving through its events…")
                    !done -> tr("Включи правила и жми «Прогнать ход».", "Switch on some rules and tap “Run the turn.”")
                    allow && deny -> tr(
                        "Черновик не открылся: один «запретить» перевешивает любое «разрешить».",
                        "The draft did not open: a single “deny” outweighs any “allow.”",
                    )
                    deny -> tr(
                        "Черновик не открылся: правило на «перед инструментом» остановило вызов.",
                        "The draft did not open: the “before tool” rule stopped the call.",
                    )
                    allow -> tr(
                        "«Разрешить» ничего не обошло: почта уходит наружу и всё равно ждала твоё «ок». Теперь включи «запретить».",
                        "“Allow” bypassed nothing: email leaves the phone, so it still waited for your “OK.” Now switch on “deny.”",
                    )
                    else -> tr(
                        "Правил нет — почта всё равно спросила «ок»: это правило по умолчанию. Повесь хук и прогони снова.",
                        "No rules, and email still asked for “OK”: that is the default rule. Attach a hook and run it again.",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 2,
            )
        }
        Button(onClick = { run++ }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(if (done) tr("Прогнать ещё раз", "Run it again") else tr("Прогнать ход", "Run the turn"))
        }
    }
}
