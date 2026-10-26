package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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

/** A character for a chip: a short name, its instruction to the model and two answers — without a profile and with one. */
private class PersonaVoice(val name: String, val instruction: String, val plain: String, val withProfile: String)

// The names and the tone come from the app's real persona catalog; the answers themselves are an example of how each character would sound.
private val VOICES = listOf(
    PersonaVoice(
        name = "Дворецкий",
        instruction = "сухо, минимум слов",
        plain = "Прогулка. Книга. Ранний сон.",
        withProfile = "Пробежка, Саша. Потом душ и сон.",
    ),
    PersonaVoice(
        name = "Компаньон",
        instruction = "тепло, по-человечески",
        plain = "Ох, вариантов куча! Уютный фильм или звонок другу — что ближе? 🙂",
        withProfile = "Саша, может, лёгкая пробежка на закате? 🙂 А потом чай и сериал!",
    ),
    PersonaVoice(
        name = "Коуч",
        instruction = "прямо, без сахара",
        plain = "Сначала дела на завтра. Потом что угодно, кроме ленты в телефоне.",
        withProfile = "Ты же бегаешь, Саша. Пять километров — и без отговорок.",
    ),
)

private const val SAMPLE_PROFILE = "Саша, любит бегать"

/**
 * "Persona and profile": the question is always the same, while the user switches the character and the
 * profile. Above the answer you can see what the model reads before it — only this instruction changes, and
 * the answer sounds different. The model is the same: the persona and the profile are just text it gets every
 * time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PersonaSwitchScene() {
    var persona by rememberSaveable { mutableIntStateOf(0) }
    var useProfile by rememberSaveable { mutableStateOf(false) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val voice = VOICES[persona]
    val primary = MaterialTheme.colorScheme.primary
    val profileAlpha by animateFloatAsState(if (useProfile) 1f else 0.4f, tween(250), label = "profileAlpha")

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PersonaBubble(text = "Чем заняться вечером?", fromUser = true)
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(1.5.dp, primary.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text("Перед ответом модель читает:", style = MaterialTheme.typography.labelMedium, color = primary, fontWeight = FontWeight.SemiBold)
            Text("Персона: ${voice.instruction}", style = MaterialTheme.typography.bodySmall, color = SceneInk, maxLines = 1)
            Text(
                text = if (useProfile) "Профиль: $SAMPLE_PROFILE" else "Профиль: не передаётся",
                style = MaterialTheme.typography.bodySmall,
                color = SceneInk,
                maxLines = 1,
                modifier = Modifier.alpha(profileAlpha),
            )
        }
        // A bubble of the same width and height for any answer: the chips and the toggle under it do not move.
        Crossfade(targetState = if (useProfile) voice.withProfile else voice.plain, animationSpec = tween(250), label = "answer") { answer ->
            PersonaBubble(text = answer, fromUser = false)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VOICES.forEachIndexed { index, item ->
                FilterChip(
                    selected = persona == index,
                    onClick = {
                        persona = index
                        touched = true
                    },
                    label = { Text(item.name) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Учитывать профиль", style = MaterialTheme.typography.bodyMedium)
                Text(SAMPLE_PROFILE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Switch(
                checked = useProfile,
                onCheckedChange = {
                    useProfile = it
                    touched = true
                },
            )
        }
        Text(
            text = if (touched) {
                "Модель одна и та же — поменялась только инструкция, которую она читает перед ответом"
            } else {
                "Переключай характер и профиль — и смотри на ответ"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
        )
    }
}

@Composable
private fun PersonaBubble(text: String, fromUser: Boolean) {
    Box(
        contentAlignment = if (fromUser) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (fromUser) 0.62f else 0.9f)
                .clip(RoundedCornerShape(12.dp))
                .background(if (fromUser) ScenePalette[0] else Color.White)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = text,
                color = SceneInk,
                style = MaterialTheme.typography.bodyMedium,
                minLines = if (fromUser) 1 else 3,
            )
        }
    }
}
