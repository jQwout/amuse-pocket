package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Деталь просьбы: подпись чипа, что она дописывает в запрос, о чём модель без неё гадает,
 * кусок ответа «наугад» и кусок ответа, когда деталь названа (пустой — кусок просто исчезает).
 */
private class AskDetail(val chip: String, val addition: String, val guess: String, val guessed: String, val exact: String)

private val ASK_DETAILS = listOf(
    AskDetail("кому: маме", " маме", "кому", "Дорогой друг!", "Мама!"),
    AskDetail("повод: день рождения", " с днём рождения", "повод", "Поздравляю с праздником!", "С днём рождения!"),
    AskDetail(
        "тон: с юмором", ", с юмором", "тон",
        "Желаю счастья, здоровья и успехов во всех начинаниях.",
        "Пусть поводов улыбнуться будет больше, чем дел в календаре!",
    ),
    AskDetail("длина: коротко", ", коротко", "длина","Пусть каждый день приносит радость, а все мечты сбываются!", ""),
)

/**
 * «Как просить»: пользователь начинает с размытого «напиши поздравление» и чипами дописывает детали.
 * Серым в ответе — то, что модель угадала сама; каждая названная деталь заменяет догадку точным куском,
 * и шкала точности заполняется. Каждая деталь в просьбе — минус одна догадка модели.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptSharpenScene() {
    var mask by rememberSaveable { mutableIntStateOf(0) }
    fun isOn(index: Int) = mask and (1 shl index) != 0
    val count = ASK_DETAILS.indices.count(::isOn)
    val precision by animateFloatAsState(count.toFloat() / ASK_DETAILS.size, tween(350), label = "precision")
    val primary = MaterialTheme.colorScheme.primary
    val request = "Напиши поздравление" + ASK_DETAILS.indices.filter(::isOn).joinToString("") { ASK_DETAILS[it].addition }
    val answer = buildAnnotatedString {
        ASK_DETAILS.forEachIndexed { index, detail ->
            val piece = if (isOn(index)) detail.exact else detail.guessed
            if (piece.isEmpty()) return@forEachIndexed
            val style = if (isOn(index)) {
                SpanStyle(color = SceneInk, fontWeight = FontWeight.SemiBold)
            } else {
                SpanStyle(color = SceneInk.copy(alpha = 0.5f), fontStyle = FontStyle.Italic)
            }
            if (length > 0) append(" ")
            withStyle(style) { append(piece) }
        }
    }
    val guesses = ASK_DETAILS.indices.filterNot(::isOn).map { ASK_DETAILS[it].guess }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Запрос растёт на глазах, но место под две строки занято заранее — чипы под ним не прыгают.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ScenePalette[0])
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("«$request»", color = SceneInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, minLines = 2)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ASK_DETAILS.forEachIndexed { index, detail ->
                FilterChip(selected = isOn(index), onClick = { mask = mask xor (1 shl index) }, label = { Text(detail.chip) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Точность", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(primary.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(precision.coerceIn(0.02f, 1f))
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(primary),
                )
            }
            Text("$count/${ASK_DETAILS.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(answer, style = MaterialTheme.typography.bodyMedium, minLines = 5)
            Text(
                text = if (guesses.isEmpty()) "Гадать не о чем — всё сказано" else "Серым — догадки: ${guesses.joinToString(", ")}",
                color = SceneInk.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when (count) {
                    0 -> "Модель гадает о четырёх вещах — добавляй детали"
                    ASK_DETAILS.size -> "Четыре детали — ноль догадок: ответ точно в цель"
                    else -> "Каждая деталь убирает догадку — осталось ${ASK_DETAILS.size - count}"
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { mask = 0 }, enabled = count > 0) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Сначала")
            }
        }
    }
}
