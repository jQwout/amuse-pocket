package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// The lesson's example: "sort out my mail and delete the junk". The email counts are for the example.
private val GATE_PLAN = listOf(
    "Прочитать входящие за месяц",
    "Отписаться от 12 рассылок",
    "Удалить 40 писем со спамом",
    "Удалить всё старше года",
)

/**
 * "Plan mode": the agent shows a four-step plan, and all steps are locked. Before "Approve" the user can cross
 * out a step with a tap — and that is free. After the "ok" the locks open one by one, only the approved steps
 * run, and the agent does not touch the crossed-out ones.
 */
@Composable
internal fun PlanGateScene() {
    var struck by rememberSaveable { mutableIntStateOf(0) }
    var approved by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    LaunchedEffect(approved) {
        progress = 0
        if (!approved) return@LaunchedEffect
        GATE_PLAN.indices.forEach { i ->
            // A crossed-out step is passed quickly: it is not run, only the skip is marked.
            delay(if (struck and (1 shl i) != 0) 300 else 750)
            progress = i + 1
        }
    }
    val done = approved && progress == GATE_PLAN.size
    val struckCount = GATE_PLAN.indices.count { struck and (1 shl it) != 0 }
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Задача: «разбери мою почту и удали лишнее»", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val gateBg by animateColorAsState(if (approved) primary.copy(alpha = 0.14f) else Color.White, tween(300), label = "gate")
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(gateBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(if (approved) Icons.Default.LockOpen else Icons.Default.Lock, contentDescription = null, tint = SceneInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (approved) "Гейт открыт: выполняю только одобренное" else "Гейт закрыт: инструменты выключены",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = SceneInk,
                maxLines = 1,
            )
        }
        Text(
            "Агент: «Вот план. Вычеркни, с чем не согласен, — и скажи „ок“»",
            style = MaterialTheme.typography.bodyMedium,
            minLines = 2,
        )
        GATE_PLAN.forEachIndexed { i, step ->
            GateRow(
                number = i + 1,
                text = step,
                struck = struck and (1 shl i) != 0,
                approved = approved,
                reached = i < progress,
                onTap = if (approved) null else { { struck = struck xor (1 shl i) } },
            )
        }
        Text(
            text = when {
                !approved -> "Пока нет «ок», агент не тронул ни одного письма. Тапни шаг, чтобы вычеркнуть"
                !done -> "Выполняю…"
                struckCount == GATE_PLAN.size -> "Ты вычеркнул всё — агент ничего не сделал. Ни одного действия без твоего «ок»"
                struckCount > 0 -> "Сделано ${GATE_PLAN.size - struckCount} из ${GATE_PLAN.size}, вычеркнутое не тронуто: курс поправлен до действий — бесплатно"
                else -> "Всё по плану, 4 из 4. Удалённое уже не вернуть — поправить курс можно было только до «ок»"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
            color = if (done) primary else MaterialTheme.colorScheme.outline,
            minLines = 3,
            maxLines = 3,
        )
        Button(
            onClick = {
                if (done) {
                    approved = false
                    struck = 0
                } else {
                    approved = true
                }
            },
            enabled = !approved || done,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    !approved -> "Одобрить"
                    done -> "Сначала"
                    else -> "Выполняю…"
                },
            )
        }
    }
}

@Composable
private fun GateRow(number: Int, text: String, struck: Boolean, approved: Boolean, reached: Boolean, onTap: (() -> Unit)?) {
    val doneStep = reached && !struck
    val background by animateColorAsState(if (doneStep) ScenePalette[2] else Color.White, tween(300), label = "gateRow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, SceneInk.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier)
            .padding(horizontal = 10.dp),
    ) {
        Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SceneInk, modifier = Modifier.width(20.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = SceneInk,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
            maxLines = 1,
            modifier = Modifier.weight(1f).alpha(if (struck) 0.45f else 1f),
        )
        when {
            struck && reached -> Text("пропуск", style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f))
            doneStep -> Icon(Icons.Default.Check, contentDescription = "сделано", tint = SceneInk, modifier = Modifier.size(20.dp))
            approved && !struck -> Icon(Icons.Default.LockOpen, contentDescription = "открыт", tint = SceneInk.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Default.Lock, contentDescription = "под замком", tint = SceneInk.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}
