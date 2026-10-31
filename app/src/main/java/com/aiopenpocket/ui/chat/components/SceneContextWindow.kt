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
import com.aiopenpocket.i18n.tr

// Both languages stay in the constructor so two lines are equal when both texts are (the scene compares them),
// and the text itself is picked when the line is drawn, not when CHAT is built.
private data class Line(val fromUser: Boolean, private val ru: String, private val en: String) {
    val text: String get() = tr(ru, en)
}

// The scene's conversation. The name is said in the very first message — and by the last question it no longer fits in the window.
private val CHAT = listOf(
    Line(true, "Привет! Меня зовут Ваня", "Hi! My name is Sam"),
    Line(false, "Привет, Ваня!", "Hi, Sam!"),
    Line(true, "Посоветуй фильм на вечер", "Suggest a movie for tonight"),
    Line(false, "«Интерстеллар» — если есть три часа", "“Interstellar,” if you have three hours"),
    Line(true, "А покороче?", "Something shorter?"),
    Line(false, "Тогда «Остров собак», полтора часа", "Then “Isle of Dogs,” an hour and a half"),
    Line(true, "Спасибо! А как меня зовут?", "Thanks! What's my name?"),
)

/** How many messages fit in the scene's window. A real window is tokens, not pieces; the principle is the same. */
private const val WINDOW = 4

/**
 * "Context window": the user scrolls through the conversation, and old messages slide past the edge of the
 * window — the model no longer sees them. The last question is "what is my name?". Without long-term memory
 * the model has already lost the name; a toggle puts it into the window as a fact, and the answer changes
 * before your eyes.
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
            Text(tr("За окном — модель этого уже не видит", "Outside the window, the model can no longer see this"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
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
            Text(tr("Окно контекста: $WINDOW сообщения", "Context window: $WINDOW messages"), style = MaterialTheme.typography.labelMedium, color = primary, fontWeight = FontWeight.SemiBold)
            AnimatedVisibility(visible = remembered, enter = fadeIn() + expandVertically()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(ScenePalette[2])
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(tr("Долгая память: имя — Ваня", "Long-term memory: name is Sam"), color = SceneInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            inWindow.forEach { ChatBubble(it, faded = false) }
        }
        if (finished) {
            val knows = remembered || nameInWindow
            ChatBubble(
                Line(false, if (knows) "Тебя зовут Ваня" else "Не знаю — ты не говорил", if (knows) "Your name is Sam" else "I don't know. You didn't tell me"),
                faded = false,
                accent = if (knows) primary else MaterialTheme.colorScheme.error,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Запомнить имя в долгую память", "Save the name to long-term memory"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = remembered, onCheckedChange = { remembered = it })
        }
        Button(onClick = { shown = if (finished) 1 else shown + 1 }, modifier = Modifier.fillMaxWidth()) {
            Text(if (finished) tr("Сначала", "Start over") else tr("Следующее сообщение", "Next message"))
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
