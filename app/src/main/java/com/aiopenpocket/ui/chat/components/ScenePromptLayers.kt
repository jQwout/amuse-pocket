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
import java.util.Locale

/** Скрытый слой пакета: как он зовётся, кусочек его текста и сколько примерно токенов он весит. */
private class PackLayer(val name: String, val sample: String, val tokens: Int)

// Пример из урока: Саша не ест мясо. Токены — порядок величины для примера, а не замер настоящего пакета.
private val PACK_LAYERS = listOf(
    PackLayer("Правила", "Ты — карманный агент, отвечай по-русски…", 600),
    PackLayer("Персона", "Тон: тёплый, по-человечески", 150),
    PackLayer("Профиль", "Саша, не ест мясо", 60),
    PackLayer("Память", "Саше понравилась паста с грибами", 90),
    PackLayer("История", "Вчерашний разговор: 12 сообщений", 850),
)

private const val USER_PROMPT = "Посоветуй ужин"
private const val USER_PROMPT_TOKENS = 5
private const val PROFILE_LAYER = 2
private const val MEMORY_LAYER = 3

/**
 * «Что видит модель»: пользователь написал два слова и включает слои, которые приложение незаметно
 * кладёт перед ними, — правила, персону, профиль, память, историю. Пакет растёт, счётчик токенов
 * улетает в сотни раз выше, а ответ из «мимо» становится точным: модель читает куда больше, чем ты набрал.
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
        Text("Ты пишешь: «$USER_PROMPT». Что приложение добавит от себя?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PACK_LAYERS.forEachIndexed { index, layer ->
                FilterChip(selected = isOn(index), onClick = { mask = mask xor (1 shl index) }, label = { Text(layer.name) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TokenTally(label = "ты написал", tokens = USER_PROMPT_TOKENS, modifier = Modifier.weight(1f))
            TokenTally(label = "модель читает", tokens = shownTotal, modifier = Modifier.weight(1f))
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
            Text("Что видит модель", style = MaterialTheme.typography.labelMedium, color = primary, fontWeight = FontWeight.SemiBold)
            PACK_LAYERS.forEachIndexed { index, layer ->
                AnimatedVisibility(visible = isOn(index), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    PackRow(name = layer.name, text = layer.sample, tokens = layer.tokens, fill = ScenePalette[(index + 1) % ScenePalette.size])
                }
            }
            PackRow(name = "Ты", text = USER_PROMPT, tokens = USER_PROMPT_TOKENS, fill = ScenePalette[0])
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
                    isOn(MEMORY_LAYER) -> "Ответ: паста с грибами — тебе она понравилась в прошлый раз"
                    isOn(PROFILE_LAYER) -> "Ответ: овощное карри — сытно и без мяса"
                    else -> "Ответ: курица с рисом — быстро и сытно"
                },
                color = SceneInk,
                style = MaterialTheme.typography.bodyMedium,
                minLines = 2,
            )
        }
        Text(
            text = when {
                mask == 0 -> "Модель видит только два слова — и не знает, что Саша не ест мясо"
                mask == (1 shl PACK_LAYERS.size) - 1 -> "Ты написал ~$USER_PROMPT_TOKENS токенов, а модель прочитала ~${formatTokens(total)}: остальное добавило приложение"
                !knowsTaste -> "Без профиля и памяти модель не знает про мясо — совет мимо"
                else -> "Каждый слой — текст, который модель читает вместе с твоим вопросом"
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
        Text("$label, токенов", color = SceneInk.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
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

/** 1755 → «1 755»: неразрывный пробел между тысячами, как пишут по-русски. */
private fun formatTokens(value: Int): String = String.format(Locale.US, "%,d", value).replace(',', ' ')
