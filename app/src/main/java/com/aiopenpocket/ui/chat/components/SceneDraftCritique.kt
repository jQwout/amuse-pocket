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
import com.aiopenpocket.i18n.tr

/** A piece of the draft; [note] is the number of the critique remark aimed at it (null — no complaints about this piece). */
private class DraftPiece(val text: String, val note: Int? = null)

// The request from the lesson's scenario — a polite refusal to an investor. The draft has three typical flaws on purpose.
// Functions, not top-level vals: tr() has to run when the scene is shown, in the current language.
private fun draftPieces() = listOf(
    DraftPiece(tr("Здравствуйте! ", "Hello! ")),
    DraftPiece(
        tr("Спасибо за письмо, мы очень-очень внимательно прочитали всё, что вы прислали. ", "Thank you for your letter, we have very, very carefully read everything you sent us. "),
        note = 0,
    ),
    DraftPiece(tr("Но нам это не подходит. ", "But this is not a fit for us. "), note = 1),
    DraftPiece(tr("Может, как-нибудь потом.", "Maybe sometime later."), note = 2),
)
private fun rewritten() = tr(
    "Здравствуйте! Спасибо за предложение. Сейчас мы не привлекаем инвестиции — команда занята запуском. Будем рады вернуться к разговору весной.",
    "Hello! Thank you for the proposal. We are not taking on investment right now, because the team is busy with a launch. We would be glad to pick the conversation up in spring.",
)

// The same kinds of weak spots the lesson names: superfluous, missing, unclear. A checklist item for each.
private fun notes() = listOf(
    tr("Лишнее: вода, письмо раздуто", "Extra: filler, the letter is padded"),
    tr("Упущено: нет причины — звучит резко", "Missing: no reason, so it sounds harsh"),
    tr("Неясно: когда это «потом»?", "Unclear: when is “later”?"),
)
private fun checks() = listOf(tr("коротко", "short"), tr("с причиной", "has a reason"), tr("ясно", "clear"))
private val MARKS = listOf(ScenePalette[4], ScenePalette[1], ScenePalette[3])
private fun reflectionPhases() = listOf(tr("Черновик", "Draft"), tr("Критика", "Critique"), tr("Итог", "Final"))
private val FixedGreen = Color(0xFF2E7D32)

/**
 * "Reflection": the user leads the agent through three passes themselves. "Critique" highlights the weak
 * spots right in the draft and writes short remarks about them, "Rewrite" puts the result in place — and the
 * checklist turns green. The model call counter grows with each pass: the quality is bought with two extra
 * calls.
 */
@Composable
internal fun DraftCritiqueScene() {
    // 0 — draft, 1 — critique, 2 — result.
    var phase by rememberSaveable { mutableIntStateOf(0) }
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val notesAlpha by animateFloatAsState(if (phase >= 1) 1f else 0f, tween(300), label = "notes")

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(tr("Запрос: «напиши вежливый отказ инвестору»", "Request: “write a polite refusal to an investor”"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            reflectionPhases().forEachIndexed { index, name ->
                PhasePill(name = name, reached = index <= phase, color = primary, modifier = Modifier.weight(1f))
            }
        }
        Text(
            text = buildAnnotatedString {
                append(tr("Вызовов модели: ${phase + 1}", "Model calls: ${phase + 1}"))
                if (phase > 0) {
                    withStyle(SpanStyle(color = error, fontWeight = FontWeight.Bold)) {
                        append(if (phase == 1) tr("   +1 вызов", "   +1 call") else tr("   +2 вызова", "   +2 calls"))
                    }
                }
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(12.dp),
        ) {
            Text(if (phase == 2) tr("Итог", "Final") else tr("Черновик", "Draft"), style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f))
            Text(
                text = if (phase == 2) AnnotatedString(rewritten()) else draftText(marked = phase == 1, numberColor = error),
                style = MaterialTheme.typography.bodyMedium,
                color = SceneInk,
                minLines = 5,
            )
        }
        // The remarks are visible only after the critique, but their space is always taken — the buttons do not jump.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.alpha(notesAlpha)) {
            notes().forEachIndexed { index, note -> NoteRow(number = index + 1, text = note, mark = MARKS[index], fixed = phase == 2) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            checks().forEach { label -> CheckChip(label = label, phase = phase, error = error, modifier = Modifier.weight(1f)) }
        }
        // Two buttons in their places: after the result the left one turns into "Start over" but does not move.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { phase = if (phase == 2) 0 else 1 }, enabled = phase != 1, modifier = Modifier.weight(1f)) {
                Text(if (phase == 2) tr("Сначала", "Start over") else tr("Критика", "Critique"))
            }
            Button(onClick = { phase = 2 }, enabled = phase == 1, modifier = Modifier.weight(1f)) { Text(tr("Переписать", "Rewrite")) }
        }
        Text(
            text = when (phase) {
                0 -> tr("Это первый проход. Нажми «Критика» — агент перечитает свой черновик", "This is the first pass. Tap “Critique” and the agent rereads its own draft")
                1 -> tr("Критика нашла три слабых места — и пока ничего не переписала", "The critique found three weak spots and has not rewritten anything yet")
                else -> tr("Итог лучше черновика, но это минимум три вызова модели вместо одного. Для важного письма — окупается", "The final is better than the draft, but it takes at least three model calls instead of one. For an important letter, it pays off")
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (phase == 2) FontWeight.SemiBold else FontWeight.Normal,
            minLines = 3,
        )
    }
}

private fun draftText(marked: Boolean, numberColor: Color): AnnotatedString = buildAnnotatedString {
    draftPieces().forEach { piece ->
        val note = piece.note
        if (!marked || note == null) {
            append(piece.text)
            return@forEach
        }
        withStyle(SpanStyle(background = MARKS[note], textDecoration = TextDecoration.Underline)) { append(piece.text.trimEnd()) }
        // The remark's number right at the phrase — it shows exactly what the complaint is about.
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
        // After the rewrite the remark is crossed out — it is taken into account in the result.
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
