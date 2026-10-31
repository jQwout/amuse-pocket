package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.delay

/** A step on the track: what was done, what the executor said while doing it and whether the step failed. */
private class WfMove(val title: String, val note: String, val failed: Boolean = false)

// The lesson's example: a photo of a document came in — recognize it, translate it, make a summary.
// Getters, not stored lists: tr() has to run when the scene is drawn, so a language switch is seen at once.
private val RAIL_MOVES: List<WfMove>
    get() = listOf(
        WfMove(tr("Распознать текст", "Recognize the text"), tr("шаг 1 по сценарию — распознаю", "step 1 of the script — recognizing")),
        WfMove(tr("Перевести", "Translate"), tr("шаг 2 по сценарию — перевожу", "step 2 of the script — translating")),
        WfMove(tr("Сделать выжимку", "Make a summary"), tr("шаг 3 по сценарию — сокращаю. Конец маршрута", "step 3 of the script — summarizing. End of the route")),
    )
private val RAIL_SURPRISE: List<WfMove>
    get() = listOf(
        WfMove(
            tr("Распознать текст", "Recognize the text"),
            tr("вместо текста каша, а шага «повернуть фото» в сценарии нет", "garbage instead of text, and the script has no “rotate the photo” step"),
            failed = true,
        ),
    )
// Every time the agent talks through its choice: what it sees and which step it takes next.
private val AGENT_MOVES: List<WfMove>
    get() = listOf(
        WfMove(tr("Распознать текст", "Recognize the text"), tr("сначала нужен текст — выбираю «распознать»", "I need the text first — choosing “recognize”")),
        WfMove(tr("Перевести", "Translate"), tr("текст есть — выбираю «перевести»", "I have the text — choosing “translate”")),
        WfMove(
            tr("Сделать выжимку", "Make a summary"),
            tr("перевод готов — выбираю «выжимку». Цель достигнута, стоп", "translation done — choosing “summary”. Goal reached, stop"),
        ),
    )
private val AGENT_SURPRISE: List<WfMove>
    get() = listOf(
        WfMove(
            tr("Распознать текст", "Recognize the text"),
            tr("выбираю «распознать»… а вместо текста каша", "choosing “recognize”… but it's garbage instead of text"),
            failed = true,
        ),
        WfMove(
            tr("Повернуть фото", "Rotate the photo"),
            tr("похоже, фото вверх ногами — выбираю «повернуть»", "the photo looks upside down — choosing “rotate”"),
        ),
        WfMove(
            tr("Распознать ещё раз", "Recognize again"),
            tr("теперь читается — снова выбираю «распознать»", "it's readable now — choosing “recognize” again"),
        ),
        WfMove(tr("Перевести", "Translate"), tr("текст есть — выбираю «перевести»", "I have the text — choosing “translate”")),
        WfMove(
            tr("Сделать выжимку", "Make a summary"),
            tr("перевод готов — выбираю «выжимку». Цель достигнута, стоп", "translation done — choosing “summary”. Goal reached, stop"),
        ),
    )

/** The agent with a surprise has the most steps: space is kept for them so the buttons do not jump. */
private const val WF_MAX_ROWS = 5

/**
 * "Workflow and agent": one task from the lesson — a document photo → a translation and a summary — on two
 * tabs. The workflow's rails are visible before the run, while the agent's steps appear along the way: it
 * picks them itself. "Surprise" slips in an upside-down photo: the rails stop, while the agent inserts "rotate"
 * on its own — at the cost of extra decisions.
 */
@Composable
internal fun WorkflowVsAgentScene() {
    var agent by rememberSaveable { mutableStateOf(false) }
    var surprise by rememberSaveable { mutableStateOf(false) }
    var runs by rememberSaveable { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(0) }
    val moves = when {
        !agent && !surprise -> RAIL_MOVES
        !agent -> RAIL_SURPRISE
        !surprise -> AGENT_MOVES
        else -> AGENT_SURPRISE
    }
    // Switching the tab repeats the same run on the other one: one task, two executors — easy to compare.
    LaunchedEffect(agent, surprise, runs) {
        shown = 0
        if (runs == 0) return@LaunchedEffect
        for (i in 1..moves.size) {
            delay(650)
            shown = i
        }
    }
    val done = runs > 0 && shown == moves.size
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Задача: прислали фото документа — нужен перевод и выжимка", "The task: a photo of a document came in — it needs a translation and a summary"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !agent, onClick = { agent = false }, label = { Text(tr("Воркфлоу", "Workflow")) })
            FilterChip(selected = agent, onClick = { agent = true }, label = { Text(tr("Агент", "Agent")) })
        }
        Text(
            text = if (agent) {
                tr("Агент выбирает шаги сам · решений: $shown", "The agent picks the steps itself · decisions: $shown")
            } else {
                tr("Шаги прописаны заранее · решений: 0", "Steps are set in advance · decisions: 0")
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().height((WF_MAX_ROWS * 36 + (WF_MAX_ROWS - 1) * 4).dp),
        ) {
            if (agent) {
                if (shown == 0) {
                    Text(tr("Агент ещё не знает, какие будут шаги — решит по ходу", "The agent doesn't know the steps yet — it will decide as it goes"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                moves.take(shown).forEachIndexed { index, move -> WfRow(index + 1, move.title, reached = true, failed = move.failed) }
            } else {
                // The rails are visible in full before the run: the route is known in advance — that is the whole point.
                RAIL_MOVES.forEachIndexed { index, move ->
                    val reached = index < shown
                    WfRow(index + 1, move.title, reached = reached, failed = reached && moves[index].failed)
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = when {
                    shown > 0 -> "${if (agent) tr("Агент", "Agent") else tr("Воркфлоу", "Workflow")}: ${moves[shown - 1].note}"
                    runs == 0 -> tr("Нажми «Запустить» — или подкинь сюрприз", "Tap “Run” — or throw in a surprise")
                    else -> "…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SceneInk,
                minLines = 2,
                maxLines = 2,
            )
        }
        Text(
            text = when {
                !done -> " "
                !agent && !surprise -> tr("Те же шаги в том же порядке — предсказуемо и дёшево", "The same steps in the same order — predictable and cheap")
                !agent -> tr("Рельсы встали: на такой случай ветки нет", "The rails stopped: there is no branch for this case")
                !surprise -> tr("Дошёл, но каждый шаг выбирал сам — и каждое решение стоит запроса к модели", "It got there, but it chose every step itself — and every decision costs a model request")
                else -> tr("Сам вставил «повернуть фото» и дошёл — гибко. Но решений уже пять, и каждое стоит запроса", "It added “rotate the photo” on its own and got there — flexible. But that is already five decisions, and each costs a request")
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (done && !agent && surprise) error else primary,
            minLines = 2,
            maxLines = 2,
        )
        Text(
            text = if (done) tr("Задача чёткая и повторяется — воркфлоу. План заранее неизвестен — агент", "A clear, repeating task — workflow. A plan unknown in advance — agent") else " ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            minLines = 2,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { surprise = false; runs++ }, modifier = Modifier.weight(1f)) { Text(tr("Запустить", "Run")) }
            OutlinedButton(onClick = { surprise = true; runs++ }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Сюрприз", "Surprise"))
            }
        }
    }
}

@Composable
private fun WfRow(number: Int, title: String, reached: Boolean, failed: Boolean) {
    val error = MaterialTheme.colorScheme.error
    val background by animateColorAsState(
        when {
            failed -> Color.White
            reached -> ScenePalette[2]
            else -> Color.White
        },
        tween(250),
        label = "wfRow",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .alpha(if (reached) 1f else 0.55f)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(2.dp, if (failed) error else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(22.dp).clip(CircleShape).background(SceneInk.copy(alpha = 0.08f)),
        ) {
            Text("$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = SceneInk)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = SceneInk, maxLines = 1, modifier = Modifier.weight(1f))
        if (reached) {
            Icon(
                imageVector = if (failed) Icons.Default.Close else Icons.Default.Check,
                contentDescription = if (failed) tr("сорвался", "failed") else tr("готово", "done"),
                tint = if (failed) error else SceneInk,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
