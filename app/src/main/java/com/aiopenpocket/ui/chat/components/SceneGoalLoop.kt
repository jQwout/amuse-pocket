package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.roundToInt

/** An iteration: what the agent did, what proved the check and which criteria are closed after it. */
private class GoalTurn(val action: String, val evidence: String, val passed: List<Boolean>)

// The lesson's example: a shopping list for borscht and a reminder for the evening.
private val GOAL_CRITERIA = listOf("есть список", "в нём всё для борща", "напоминание на вечер")
private val GOAL_TURNS = listOf(
    GoalTurn("Список: картошка, капуста, морковь", "свёклы в списке нет", listOf(true, false, false)),
    GoalTurn("Добавил свёклу, лук и томатную пасту", "продукты есть, напоминания нет", listOf(true, true, false)),
    GoalTurn("Поставил напоминание «борщ» на 19:00", "напоминание стоит на 19:00", listOf(true, true, true)),
)
private const val GOAL_ROW_H = 68

/**
 * "Goal mode": the goal has three checkable criteria, and a slider sets the iteration limit. "Run" spins the
 * loop: an action → a check against the criteria (✓/✗) → the next iteration better than the last. Too few
 * iterations — the step honestly stops halfway and waits for a person; enough — every criterion is closed with
 * evidence.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GoalLoopScene() {
    var limit by rememberSaveable { mutableFloatStateOf(3f) } // three by default — as in the app
    var runs by rememberSaveable { mutableIntStateOf(0) }
    var attempted by remember { mutableIntStateOf(0) }
    var checked by remember { mutableIntStateOf(0) }
    val turnsLimit = limit.roundToInt()
    val target = min(turnsLimit, GOAL_TURNS.size)
    LaunchedEffect(runs) {
        attempted = 0
        checked = 0
        if (runs == 0) return@LaunchedEffect
        for (i in 1..target) {
            delay(500)
            attempted = i
            delay(800)
            checked = i
        }
    }
    val finished = runs > 0 && checked == target
    val running = runs > 0 && !finished
    val last = GOAL_TURNS.getOrNull(checked - 1)
    val reached = last?.passed?.all { it } == true
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Цель: список покупок для борща и напоминание на вечер", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("Готово, когда:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            GOAL_CRITERIA.forEachIndexed { i, name ->
                val ok = last?.passed?.get(i) == true
                val bg by animateColorAsState(if (ok) ScenePalette[2] else Color.White, tween(300), label = "criterion")
                Text(
                    "${i + 1} · $name",
                    style = MaterialTheme.typography.labelMedium,
                    color = SceneInk,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Лимит витков: $turnsLimit", style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                "страховка от вечной петли",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        Slider(
            value = limit,
            onValueChange = {
                limit = it
                runs = 0 // a new limit — a new run; old iterations are not mixed with new ones
            },
            valueRange = 1f..5f,
            steps = 3,
            enabled = !running,
        )
        // Space for three iterations is always kept: the button under them does not slide while the loop spins.
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().height((GOAL_TURNS.size * GOAL_ROW_H + (GOAL_TURNS.size - 1) * 4).dp),
        ) {
            GOAL_TURNS.take(attempted).forEachIndexed { i, turn ->
                val appear = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(visibleState = appear, enter = fadeIn() + expandVertically()) {
                    GoalTurnRow(number = i + 1, turn = turn, checked = i < checked)
                }
            }
        }
        Text(
            text = when {
                !finished -> " "
                reached && turnsLimit > GOAL_TURNS.size -> "Цель на витке 3: каждый критерий закрыт доказательством — агент остановился сам, остаток лимита не понадобился"
                reached -> "Цель на витке 3: каждый критерий закрыт доказательством — агент остановился сам, ровно в лимит"
                // As in the plan engine: having hit the limit, the step is not counted and waits for a person, without "done".
                else -> "Лимит — $turnsLimit, витки кончились: не закрыто «${GOAL_CRITERIA.filterIndexed { i, _ -> last?.passed?.get(i) != true }.joinToString("», «")}». Шаг не засчитан и ждёт тебя — без притворного «готово»"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (reached) primary else MaterialTheme.colorScheme.error,
            minLines = 4,
            maxLines = 4,
        )
        Button(onClick = { runs++ }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    running -> "Агент крутит витки…"
                    finished -> "Ещё раз"
                    else -> "Запустить"
                },
            )
        }
    }
}

@Composable
private fun GoalTurnRow(number: Int, turn: GoalTurn, checked: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(GOAL_ROW_H.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Виток $number",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = SceneInk,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            GoalMarks(turn = turn, checked = checked)
        }
        Text(turn.action, style = MaterialTheme.typography.bodySmall, color = SceneInk, maxLines = 1)
        Text(
            text = if (checked) "Сверка: ${turn.evidence}" else "Сверка: …",
            style = MaterialTheme.typography.labelSmall,
            color = if (checked && !turn.passed.all { it }) MaterialTheme.colorScheme.error else SceneInk.copy(alpha = 0.6f),
            maxLines = 1,
        )
    }
}

@Composable
private fun GoalMarks(turn: GoalTurn, checked: Boolean) {
    val error = MaterialTheme.colorScheme.error
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        turn.passed.forEachIndexed { i, ok ->
            // Before the check — the criterion's number; after — ✓ or ✗: the agent does not take its own word for it.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (checked && ok) ScenePalette[2] else Color.White)
                    .border(1.5.dp, if (!checked) SceneInk.copy(alpha = 0.3f) else if (ok) ScenePalette[2] else error, CircleShape),
            ) {
                when {
                    !checked -> Text("${i + 1}", style = MaterialTheme.typography.labelSmall, color = SceneInk.copy(alpha = 0.6f))
                    ok -> Icon(Icons.Default.Check, contentDescription = "критерий ${i + 1} закрыт", tint = SceneInk, modifier = Modifier.size(14.dp))
                    else -> Icon(Icons.Default.Close, contentDescription = "критерий ${i + 1} не закрыт", tint = error, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
