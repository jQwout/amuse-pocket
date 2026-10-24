package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Кусок черновика; [note] — номер замечания критики, которое в него целит (null — к куску претензий нет). */
private class DraftPiece(val text: String, val note: Int? = null)

// Запрос из сценария урока — вежливый отказ инвестору. В черновике нарочно три типичных огреха.
private val DRAFT = listOf(
    DraftPiece("Здравствуйте! "),
    DraftPiece("Спасибо за письмо, мы очень-очень внимательно прочитали всё, что вы прислали. ", note = 0),
    DraftPiece("Но нам это не подходит. ", note = 1),
    DraftPiece("Может, как-нибудь потом.", note = 2),
)
private const val REWRITTEN = "Здравствуйте! Спасибо за предложение. Сейчас мы не привлекаем инвестиции — " +
    "команда занята запуском. Будем рады вернуться к разговору весной."

// Те же виды слабых мест, что называет урок: лишнее, упущенное, неясное. Пункт чек-листа — на каждое.
private val NOTES = listOf("Лишнее: вода, письмо раздуто", "Упущено: нет причины — звучит резко", "Неясно: когда это «потом»?")
private val CHECKS = listOf("коротко", "с причиной", "ясно")
private val MARKS = listOf(ScenePalette[4], ScenePalette[1], ScenePalette[3])
private val REFLECTION_PHASES = listOf("Черновик", "Критика", "Итог")
private val FixedGreen = Color(0xFF2E7D32)

/**
 * «Рефлексия»: пользователь сам ведёт агента по трём проходам. «Критика» подсвечивает слабые места
 * прямо в черновике и пишет к ним короткие замечания, «Переписать» ставит на место итог — и чек-лист
 * зеленеет. Счётчик вызовов модели растёт с каждым проходом: качество куплено двумя лишними вызовами.
 */
@Composable
internal fun DraftCritiqueScene() {
    // 0 — черновик, 1 — критика, 2 — итог.
    var phase by rememberSaveable { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val notesAlpha by animateFloatAsState(if (phase >= 1) 1f else 0f, tween(300), label = "notes")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Запрос: «напиши вежливый отказ инвестору»", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            REFLECTION_PHASES.forEachIndexed { index, name ->
                PhasePill(name = name, reached = index <= phase, color = primary, modifier = Modifier.weight(1f))
            }
        }
        Text(
            text = buildAnnotatedString {
                append("Вызовов модели: ${phase + 1}")
                if (phase > 0) {
                    withStyle(SpanStyle(color = error, fontWeight = FontWeight.Bold)) {
                        append(if (phase == 1) "   +1 вызов" else "   +2 вызова")
                    }
                }
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
        ) {
            Text(if (phase == 2) "Итог" else "Черновик", style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f))
            Text(
                text = if (phase == 2) AnnotatedString(REWRITTEN) else draftText(marked = phase == 1, numberColor = error),
                style = MaterialTheme.typography.bodyMedium,
                color = SceneInk,
                minLines = 5,
            )
        }
        // Замечания видны только после критики, но место под них занято всегда — кнопки не прыгают.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.alpha(notesAlpha)) {
            NOTES.forEachIndexed { index, note -> NoteRow(number = index + 1, text = note, mark = MARKS[index], fixed = phase == 2) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            CHECKS.forEach { label -> CheckChip(label = label, phase = phase, error = error, modifier = Modifier.weight(1f)) }
        }
        // Две кнопки на своих местах: левая после итога превращается в «Сначала», но не сдвигается.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { phase = if (phase == 2) 0 else 1 }, enabled = phase != 1, modifier = Modifier.weight(1f)) {
                Text(if (phase == 2) "Сначала" else "Критика")
            }
            Button(onClick = { phase = 2 }, enabled = phase == 1, modifier = Modifier.weight(1f)) { Text("Переписать") }
        }
        Text(
            text = when (phase) {
                0 -> "Это первый проход. Нажми «Критика» — агент перечитает свой черновик"
                1 -> "Критика нашла три слабых места — и пока ничего не переписала"
                else -> "Итог лучше черновика, но это три вызова модели вместо одного. Для важного письма — окупается"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (phase == 2) FontWeight.SemiBold else FontWeight.Normal,
            minLines = 3,
        )
    }
}

private fun draftText(marked: Boolean, numberColor: Color): AnnotatedString = buildAnnotatedString {
    DRAFT.forEach { piece ->
        val note = piece.note
        if (!marked || note == null) {
            append(piece.text)
            return@forEach
        }
        withStyle(SpanStyle(background = MARKS[note], textDecoration = TextDecoration.Underline)) { append(piece.text.trimEnd()) }
        // Номер замечания прямо у фразы — видно, к чему именно претензия.
        withStyle(SpanStyle(color = numberColor, fontWeight = FontWeight.Bold, fontSize = 10.sp, baselineShift = BaselineShift.Superscript)) {
            append("${note + 1}")
        }
        if (piece.text.endsWith(" ")) append(" ")
    }
}

@Composable
private fun PhasePill(name: String, reached: Boolean, color: Color, modifier: Modifier) {
    val background by animateColorAsState(if (reached) color else Color.White, tween(250), label = "phasePill")
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.clip(RoundedCornerShape(50)).background(background).padding(vertical = 6.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (reached) MaterialTheme.colorScheme.onPrimary else SceneInk,
            maxLines = 1,
        )
    }
}

@Composable
private fun NoteRow(number: Int, text: String, mark: Color, fixed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp).clip(CircleShape).background(mark)) {
            Text("$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = SceneInk)
        }
        // После переписывания замечание зачёркнуто — оно учтено в итоге.
        Text(text, style = MaterialTheme.typography.bodySmall, textDecoration = if (fixed) TextDecoration.LineThrough else null)
    }
}

@Composable
private fun CheckChip(label: String, phase: Int, error: Color, modifier: Modifier) {
    val background by animateColorAsState(if (phase == 2) ScenePalette[2] else Color.White, tween(300), label = "checkBg")
    val borderColor = when (phase) {
        0 -> SceneInk.copy(alpha = 0.15f)
        1 -> error
        else -> FixedGreen
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.5.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp),
    ) {
        when (phase) {
            1 -> Icon(Icons.Default.Close, contentDescription = null, tint = error, modifier = Modifier.size(14.dp))
            2 -> Icon(Icons.Default.Check, contentDescription = null, tint = FixedGreen, modifier = Modifier.size(14.dp))
        }
        if (phase > 0) Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = SceneInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
