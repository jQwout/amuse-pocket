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
import com.aiopenpocket.i18n.tr

/**
 * A detail of the request: the chip's label, what it adds to the request, what the model guesses about without
 * it, the piece of the "guessed" answer and the piece of the answer when the detail is named (empty — the piece
 * just disappears).
 */
private class AskDetail(
    private val chipRu: String, private val chipEn: String,
    private val additionRu: String, private val additionEn: String,
    private val guessRu: String, private val guessEn: String,
    private val guessedRu: String, private val guessedEn: String,
    private val exactRu: String, private val exactEn: String,
) {
    // A ru/en pair picked at display time: ASK_DETAILS is built once, and a tr() in its initializer would
    // freeze the language.
    val chip: String get() = tr(chipRu, chipEn)
    val addition: String get() = tr(additionRu, additionEn)
    val guess: String get() = tr(guessRu, guessEn)
    val guessed: String get() = tr(guessedRu, guessedEn)
    val exact: String get() = tr(exactRu, exactEn)
}

private val ASK_DETAILS = listOf(
    AskDetail(
        chipRu = "кому: маме", chipEn = "to: Mom",
        additionRu = " маме", additionEn = " for Mom",
        guessRu = "кому", guessEn = "who to",
        guessedRu = "Дорогой друг!", guessedEn = "Dear friend!",
        exactRu = "Мама!", exactEn = "Mom!",
    ),
    AskDetail(
        chipRu = "повод: день рождения", chipEn = "occasion: birthday",
        additionRu = " с днём рождения", additionEn = " for her birthday",
        guessRu = "повод", guessEn = "occasion",
        guessedRu = "Поздравляю с праздником!", guessedEn = "Congratulations on the holiday!",
        exactRu = "С днём рождения!", exactEn = "Happy birthday!",
    ),
    AskDetail(
        chipRu = "тон: с юмором", chipEn = "tone: funny",
        additionRu = ", с юмором", additionEn = ", with humor",
        guessRu = "тон", guessEn = "tone",
        guessedRu = "Желаю счастья, здоровья и успехов во всех начинаниях.",
        guessedEn = "Wishing you happiness, health and success in all your endeavours.",
        exactRu = "Пусть поводов улыбнуться будет больше, чем дел в календаре!",
        exactEn = "May you have more reasons to smile than things in your calendar!",
    ),
    AskDetail(
        chipRu = "длина: коротко", chipEn = "length: short",
        additionRu = ", коротко", additionEn = ", keep it short",
        guessRu = "длина", guessEn = "length",
        guessedRu = "Пусть каждый день приносит радость, а все мечты сбываются!",
        guessedEn = "May every day bring joy and every dream come true!",
        exactRu = "", exactEn = "",
    ),
)

/**
 * "How to ask": the user starts with a vague "write a greeting" and adds details with chips. Gray in the answer
 * is what the model guessed by itself; every named detail replaces a guess with a precise piece, and the
 * precision scale fills up. Every detail in the request is one guess fewer for the model.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PromptSharpenScene() {
    var mask by rememberSaveable { mutableIntStateOf(0) }
    fun isOn(index: Int) = mask and (1 shl index) != 0
    val count = ASK_DETAILS.indices.count(::isOn)
    val precision by animateFloatAsState(count.toFloat() / ASK_DETAILS.size, tween(350), label = "precision")
    val primary = MaterialTheme.colorScheme.primary
    val request = tr("Напиши поздравление", "Write a greeting") + ASK_DETAILS.indices.filter(::isOn).joinToString("") { ASK_DETAILS[it].addition }
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
        // The request grows before your eyes, but space for two lines is taken in advance — the chips under it do not jump.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ScenePalette[0])
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(tr("«$request»", "“$request”"), color = SceneInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, minLines = 2)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ASK_DETAILS.forEachIndexed { index, detail ->
                FilterChip(selected = isOn(index), onClick = { mask = mask xor (1 shl index) }, label = { Text(detail.chip) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Точность", "Precision"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(76.dp))
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
                text = if (guesses.isEmpty()) {
                    tr("Гадать не о чем — всё сказано", "Nothing left to guess — everything is said")
                } else {
                    tr(
                        "Серым — догадки: ${guesses.joinToString(", ")}",
                        "Grey marks guesses: ${guesses.joinToString(", ")}",
                    )
                },
                color = SceneInk.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when (count) {
                    0 -> tr("Модель гадает о четырёх вещах — добавляй детали", "The model is guessing four things — add details")
                    ASK_DETAILS.size -> tr("Четыре детали — ноль догадок: ответ точно в цель", "Four details, zero guesses: the answer is spot on")
                    else -> tr(
                        "Каждая деталь убирает догадку — осталось ${ASK_DETAILS.size - count}",
                        "Each detail removes a guess — ${ASK_DETAILS.size - count} left",
                    )
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { mask = 0 }, enabled = count > 0) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Сначала", "Start over"))
            }
        }
    }
}
