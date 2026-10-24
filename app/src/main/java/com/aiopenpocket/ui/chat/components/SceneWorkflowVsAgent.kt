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
import kotlinx.coroutines.delay

/** Шаг на дорожке: что сделано, что исполнитель сказал при этом и не сорвался ли шаг. */
private class WfMove(val title: String, val note: String, val failed: Boolean = false)

// Пример из урока: прислали фото документа — распознать, перевести, сделать выжимку.
private val RAIL_MOVES = listOf(
    WfMove("Распознать текст", "шаг 1 по сценарию — распознаю"),
    WfMove("Перевести", "шаг 2 по сценарию — перевожу"),
    WfMove("Сделать выжимку", "шаг 3 по сценарию — сокращаю. Конец маршрута"),
)
private val RAIL_SURPRISE = listOf(
    WfMove("Распознать текст", "вместо текста каша, а шага «повернуть фото» в сценарии нет", failed = true),
)
// Агент каждый раз проговаривает выбор: что видит и какой шаг берёт следующим.
private val AGENT_MOVES = listOf(
    WfMove("Распознать текст", "сначала нужен текст — выбираю «распознать»"),
    WfMove("Перевести", "текст есть — выбираю «перевести»"),
    WfMove("Сделать выжимку", "перевод готов — выбираю «выжимку». Цель достигнута, стоп"),
)
private val AGENT_SURPRISE = listOf(
    WfMove("Распознать текст", "выбираю «распознать»… а вместо текста каша", failed = true),
    WfMove("Повернуть фото", "похоже, фото вверх ногами — выбираю «повернуть»"),
    WfMove("Распознать ещё раз", "теперь читается — снова выбираю «распознать»"),
    WfMove("Перевести", "текст есть — выбираю «перевести»"),
    WfMove("Сделать выжимку", "перевод готов — выбираю «выжимку». Цель достигнута, стоп"),
)

/** Больше всего шагов — у агента с сюрпризом: под них и держим место, чтобы кнопки не прыгали. */
private const val WF_MAX_ROWS = 5

/**
 * «Воркфлоу и агент»: одна задача из урока — фото документа → перевод и выжимка — на двух вкладках.
 * Рельсы воркфлоу видны ещё до запуска, а шаги агента появляются по ходу: он выбирает их сам.
 * «Сюрприз» подсовывает перевёрнутое фото: рельсы встают, агент сам вставляет «повернуть» — ценой лишних решений.
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
    // Смена вкладки повторяет тот же прогон на другой: одна задача, два исполнителя — сравнивать удобно.
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
        Text("Задача: прислали фото документа — нужен перевод и выжимка", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !agent, onClick = { agent = false }, label = { Text("Воркфлоу") })
            FilterChip(selected = agent, onClick = { agent = true }, label = { Text("Агент") })
        }
        Text(
            text = if (agent) "Агент выбирает шаги сам · решений: $shown" else "Шаги прописаны заранее · решений: 0",
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
                    Text("Агент ещё не знает, какие будут шаги — решит по ходу", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                moves.take(shown).forEachIndexed { index, move -> WfRow(index + 1, move.title, reached = true, failed = move.failed) }
            } else {
                // Рельсы видны целиком ещё до запуска: маршрут известен наперёд — в этом и суть.
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
                    shown > 0 -> "${if (agent) "Агент" else "Воркфлоу"}: ${moves[shown - 1].note}"
                    runs == 0 -> "Нажми «Запустить» — или подкинь сюрприз"
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
                !agent && !surprise -> "Те же шаги в том же порядке — предсказуемо и дёшево"
                !agent -> "Рельсы встали: на такой случай ветки нет"
                !surprise -> "Дошёл, но каждый шаг выбирал сам — и каждое решение стоит запроса к модели"
                else -> "Сам вставил «повернуть фото» и дошёл — гибко. Но решений уже пять, и каждое стоит запроса"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (done && !agent && surprise) error else primary,
            minLines = 2,
            maxLines = 2,
        )
        Text(
            text = if (done) "Задача чёткая и повторяется — воркфлоу. План заранее неизвестен — агент" else " ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            minLines = 2,
            maxLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { surprise = false; runs++ }, modifier = Modifier.weight(1f)) { Text("Запустить") }
            OutlinedButton(onClick = { surprise = true; runs++ }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Сюрприз")
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
                contentDescription = if (failed) "сорвался" else "готово",
                tint = if (failed) error else SceneInk,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
