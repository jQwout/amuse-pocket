package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class Line(val fromUser: Boolean, val text: String)

// Переписка сцены. Имя звучит в самом первом сообщении — и к последнему вопросу уже не помещается в окно.
private val CHAT = listOf(
    Line(true, "Привет! Меня зовут Ваня"),
    Line(false, "Привет, Ваня!"),
    Line(true, "Посоветуй фильм на вечер"),
    Line(false, "«Интерстеллар» — если есть три часа"),
    Line(true, "А покороче?"),
    Line(false, "Тогда «Остров собак», полтора часа"),
    Line(true, "Спасибо! А как меня зовут?"),
)

/** Сколько сообщений влезает в окно сцены. Настоящее окно — это токены, а не штуки; принцип тот же. */
private const val WINDOW = 4

/**
 * «Окно контекста»: пользователь листает переписку, и старые сообщения уезжают за край окна — модель
 * их больше не видит. Последний вопрос — «как меня зовут?». Без долгой памяти модель имя уже потеряла;
 * переключатель кладёт его фактом в окно, и ответ меняется на глазах.
 */
@Composable
internal fun ContextWindowScene() {
    var shown by rememberSaveable { mutableIntStateOf(1) }
    var remembered by rememberSaveable { mutableStateOf(false) }
    val visible = CHAT.take(shown)
    val inWindow = visible.takeLast(WINDOW)
    val outside = visible.dropLast(WINDOW)
    val finished = shown == CHAT.size
    val nameInWindow = CHAT.first() in inWindow
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
        if (outside.isNotEmpty()) {
            Text("За окном — модель этого уже не видит", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            outside.forEach { ChatBubble(it, faded = true) }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, primary, RoundedCornerShape(14.dp))
                .background(primary.copy(alpha = 0.05f))
                .padding(10.dp),
        ) {
            Text("Окно контекста: $WINDOW сообщения", style = MaterialTheme.typography.labelMedium, color = primary, fontWeight = FontWeight.SemiBold)
            AnimatedVisibility(visible = remembered, enter = fadeIn() + expandVertically()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ScenePalette[2])
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text("Долгая память: имя — Ваня", color = SceneInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            inWindow.forEach { ChatBubble(it, faded = false) }
        }
        if (finished) {
            val knows = remembered || nameInWindow
            ChatBubble(
                Line(false, if (knows) "Тебя зовут Ваня" else "Не знаю — ты не говорил"),
                faded = false,
                accent = if (knows) primary else MaterialTheme.colorScheme.error,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Запомнить имя в долгую память", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = remembered, onCheckedChange = { remembered = it })
        }
        Button(onClick = { shown = if (finished) 1 else shown + 1 }, modifier = Modifier.fillMaxWidth()) {
            Text(if (finished) "Сначала" else "Следующее сообщение")
        }
    }
}

@Composable
private fun ChatBubble(line: Line, faded: Boolean, accent: Color? = null) {
    Row(
        horizontalArrangement = if (line.fromUser) Arrangement.End else Arrangement.Start,
        modifier = Modifier.fillMaxWidth().alpha(if (faded) 0.4f else 1f),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (line.fromUser) ScenePalette[0] else Color.White)
                .then(if (accent != null) Modifier.border(2.dp, accent, RoundedCornerShape(12.dp)) else Modifier)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(line.text, color = SceneInk, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
