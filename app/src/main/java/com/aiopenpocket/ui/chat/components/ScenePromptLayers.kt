package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import java.util.Locale

/**
 * A hidden layer of the package: what it is called, a piece of its text and roughly how many tokens it weighs.
 * The texts are ru/en pairs picked at display time: PACK_LAYERS is built once, and a tr() in its initializer
 * would freeze the language.
 */
private class PackLayer(
    private val nameRu: String, private val nameEn: String,
    private val sampleRu: String, private val sampleEn: String,
    val tokens: Int,
) {
    val name: String get() = tr(nameRu, nameEn)
    val sample: String get() = tr(sampleRu, sampleEn)
}

// The lesson's example: Sasha does not eat meat. The tokens are an order of magnitude for the example, not a measurement of a real package.
private val PACK_LAYERS = listOf(
    PackLayer("Правила", "Rules", "Ты — карманный агент, отвечай по-русски…", "You are a pocket agent, answer in English…", 600),
    PackLayer("Персона", "Persona", "Тон: тёплый, по-человечески", "Tone: warm, like a person", 150),
    PackLayer("Профиль", "Profile", "Саша, не ест мясо", "Sasha doesn't eat meat", 60),
    PackLayer("Память", "Memory", "Саше понравилась паста с грибами", "Sasha liked the mushroom pasta", 90),
    PackLayer("История", "History", "Вчерашний разговор: 12 сообщений", "Yesterday's conversation: 12 messages", 850),
)

private val USER_PROMPT: String get() = tr("Посоветуй ужин", "Suggest dinner ideas")
private const val USER_PROMPT_TOKENS = 5
private const val PROFILE_LAYER = 2
private const val MEMORY_LAYER = 3

/**
 * "What the model sees": the user wrote two words and switches on the layers the app quietly puts before them —
 * rules, persona, profile, memory, history. The package grows, the token counter goes hundreds of times higher,
 * and the answer goes from "off the mark" to precise: the model reads far more than you typed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptLayersScene() {
    var mask by rememberSaveable { mutableIntStateOf(0) }
    fun isOn(index: Int) = mask and (1 shl index) != 0
    val total = USER_PROMPT_TOKENS + PACK_LAYERS.indices.filter(::isOn).sumOf { PACK_LAYERS[it].tokens }
    val shownTotal by animateIntAsState(total, tween(400), label = "packTokens")
    val primary = MaterialTheme.colorScheme.primary
    val knowsTaste = isOn(PROFILE_LAYER) || isOn(MEMORY_LAYER)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            tr("Ты пишешь: «$USER_PROMPT». Что приложение добавит от себя?", "You write: “$USER_PROMPT”. What will the app add on its own?"),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PACK_LAYERS.forEachIndexed { index, layer ->
                FilterChip(selected = isOn(index), onClick = { mask = mask xor (1 shl index) }, label = { Text(layer.name) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TokenTally(label = tr("ты написал, токенов", "tokens you wrote"), tokens = USER_PROMPT_TOKENS, modifier = Modifier.weight(1f))
            TokenTally(label = tr("модель читает, токенов", "tokens the model reads"), tokens = shownTotal, modifier = Modifier.weight(1f))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, primary, RoundedCornerShape(14.dp))
                .background(primary.copy(alpha = 0.05f))
                .padding(8.dp),
        ) {
            Text(tr("Что видит модель", "What the model sees"), style = MaterialTheme.typography.labelMedium, color = primary, fontWeight = FontWeight.SemiBold)
            PACK_LAYERS.forEachIndexed { index, layer ->
                AnimatedVisibility(visible = isOn(index), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    PackRow(name = layer.name, text = layer.sample, tokens = layer.tokens, fill = ScenePalette[(index + 1) % ScenePalette.size])
                }
            }
            PackRow(name = tr("Ты", "You"), text = USER_PROMPT, tokens = USER_PROMPT_TOKENS, fill = ScenePalette[0])
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .border(2.dp, if (knowsTaste) primary else MaterialTheme.colorScheme.error, RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = when {
                    isOn(MEMORY_LAYER) -> tr(
                        "Ответ: паста с грибами — тебе она понравилась в прошлый раз",
                        "Answer: mushroom pasta — you liked it last time",
                    )
                    isOn(PROFILE_LAYER) -> tr("Ответ: овощное карри — сытно и без мяса", "Answer: vegetable curry — filling and meat-free")
                    else -> tr("Ответ: курица с рисом — быстро и сытно", "Answer: chicken with rice — quick and filling")
                },
                color = SceneInk,
                style = MaterialTheme.typography.bodyMedium,
                minLines = 2,
            )
        }
        Text(
            text = when {
                mask == 0 -> tr(
                    "Модель видит только два слова — и не знает, что Саша не ест мясо",
                    "The model sees only your request — and doesn't know that Sasha doesn't eat meat",
                )
                mask == (1 shl PACK_LAYERS.size) - 1 -> tr(
                    "Ты написал ~$USER_PROMPT_TOKENS токенов, а модель прочитала ~${formatTokens(total)}: остальное добавило приложение",
                    "You wrote ~$USER_PROMPT_TOKENS tokens, the model read ~${formatTokens(total)}: the app added the rest",
                )
                !knowsTaste -> tr(
                    "Без профиля и памяти модель не знает про мясо — совет мимо",
                    "Without the profile and memory the model knows nothing about the meat — the advice misses the mark",
                )
                else -> tr(
                    "Каждый слой — текст, который модель читает вместе с твоим вопросом",
                    "Every layer is text the model reads together with your question",
                )
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
        )
    }
}

@Composable
private fun TokenTally(label: String, tokens: Int, modifier: Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(vertical = 6.dp),
    ) {
        Text("~${formatTokens(tokens)}", color = SceneInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = SceneInk.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun PackRow(name: String, text: String, tokens: Int, fill: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(name, color = SceneInk, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.width(62.dp))
        Text(text, color = SceneInk, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("~$tokens", color = SceneInk.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 1755 → "1 755" in Russian (a non-breaking space between thousands), "1,755" in English. */
private fun formatTokens(value: Int): String {
    val grouped = String.format(Locale.US, "%,d", value)
    return tr(grouped.replace(',', '\u00a0'), grouped)
}
