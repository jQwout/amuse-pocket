package com.aiopenpocket.ui.chat.components

// @trace driven-ui-cards-0418d0
//
// Lesson step cards driven by the lesson controller: quiz and completion, reveal, concept, example, and the
// interactive fill / sort / match / scenario steps that advance the lesson when solved.

import com.aiopenpocket.ui.chat.DrivenUiCard
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.roadmap.components.QuizContent
import com.aiopenpocket.ui.roadmap.components.stableShuffledIndices
import java.util.Locale

@Composable
fun LessonQuizCardView(
    card: DrivenUiCard.LessonQuizCard,
    onAllAnswered: () -> Unit,
    savedState: String? = null,
    onStateChanged: (String?) -> Unit = {},
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = tr("Закрепим пройденное", "Let's review"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QuizContent(
            questions = card.questions,
            onAllAnswered = onAllAnswered,
            savedState = savedState,
            onStateChanged = onStateChanged,
        )
    }
}

@Composable
fun LessonCompleteCardView(
    card: DrivenUiCard.LessonCompleteCard,
    onComplete: () -> Unit,
    onOpenAgentBuilder: () -> Unit = {},
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = CardSuccessGreen,
            modifier = Modifier.size(48.dp)
        )
        Text(
            text = card.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (card.subtitle.isNotBlank()) {
            Text(
                text = card.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.outline
            )
        }
        // The Academy capstone (Task 23): rendered ONLY when the card itself was built by
        // LessonChatController.postCompletion for agent_blueprint — ordinary lessons set no primaryAction,
        // so nothing appears here and the old behavior does not change.
        card.primaryAction?.let { action ->
            Button(onClick = onOpenAgentBuilder, modifier = Modifier.fillMaxWidth()) {
                Text(action.label)
            }
        }
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
            Text(card.buttonLabel)
        }
    }
}

/** "reveal": a question; a button reveals the answer. The step advances with the "Next" button. */
@Composable
fun RevealCardView(card: DrivenUiCard.RevealCard) {
    // rememberSaveable: the reveal survives the item being recycled in LazyColumn and a rotation
    // (a plain remember reset the card to "closed" when scrolled off screen and back).
    var revealed by rememberSaveable(card) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = card.question,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AnimatedVisibility(visible = revealed) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lightbulb, null, tint = CardSuccessGreen, modifier = Modifier.size(20.dp))
                Text(text = card.answer, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (!revealed) {
            Button(onClick = { revealed = true }) {
                Icon(Icons.Default.Visibility, null)
                Spacer(Modifier.width(8.dp))
                Text(tr("Показать ответ", "Show answer"))
            }
        }
    }
}

/** "concept": a term, a definition, an example. A static card. */
@Composable
fun ConceptCardView(card: DrivenUiCard.ConceptCard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CardTitleRow(icon = Icons.Default.School, title = card.term)
        CardDivider()
        Text(text = card.definition, style = MaterialTheme.typography.bodyMedium)
        if (card.example.isNotBlank()) {
            Text(
                text = tr("Пример: ${card.example}", "Example: ${card.example}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** "example": a heading and a breakdown into parts (text + a role badge + a note). A static card. */
@Composable
fun ExampleCardView(card: DrivenUiCard.ExampleCard) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.label.isNotBlank()) {
            Text(
                text = card.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CardDivider()
        }
        card.parts.forEach { part ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (part.role.isNotBlank()) {
                    Text(
                        text = part.role.uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(text = part.text, style = MaterialTheme.typography.bodyMedium)
                if (part.note.isNotBlank()) {
                    Text(
                        text = part.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

/** How many misses on a fill step we tolerate before showing the answer and letting the learner move on. */
private const val FILL_MISSES_BEFORE_REVEAL = 2

/**
 * "fill": fill in the gap. A correct input advances the lesson (like a passed quiz).
 *
 * After [FILL_MISSES_BEFORE_REVEAL] misses the card shows the answer and a "Next" button: fill has no "Next"
 * of its own, and a learner who did not guess the exact word used to be stuck in the lesson forever. A
 * peeked answer advances the lesson the same way as a solved one — the point of the step is to read the
 * right word, not an exam.
 */
@Composable
fun FillCardView(card: DrivenUiCard.FillCard, onSolved: () -> Unit) {
    // rememberSaveable: being solved (and the draft) survive recycling/rotation. Otherwise the card was reset to
    // unsolved, and solving it again called onSolved ONE MORE TIME — the lesson cursor jumped an extra step.
    // onSolved is called only on the solved=false→true transition, so a restored solved=true does not
    // advance again. The miss counter is saveable too: otherwise a screen rotation would hide an answer
    // already shown.
    var input by rememberSaveable(card) { mutableStateOf("") }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var revealed by rememberSaveable(card) { mutableStateOf(false) }
    var misses by rememberSaveable(card) { mutableIntStateOf(0) }
    var wrong by remember(card) { mutableStateOf(false) }

    fun check() {
        if (card.accepts(input)) { solved = true; wrong = false; onSolved() } else { wrong = true; misses++ }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = card.template.replace("___", "  _____  "),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (solved) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // A peeked answer gets no green checkmark: honest that it was read, not guessed.
                if (revealed) {
                    Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.outline)
                } else {
                    Icon(Icons.Default.Check, null, tint = CardSuccessGreen)
                }
                Text(card.answer, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; wrong = false },
                singleLine = true,
                isError = wrong,
                placeholder = { Text(tr("Впиши пропущенное слово", "Type the missing word")) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (misses >= FILL_MISSES_BEFORE_REVEAL) {
                Text(
                    tr("Правильно: «${card.answer}»", "Correct answer: “${card.answer}”"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (wrong) {
                Text(tr("Не то — попробуй ещё", "Not quite — try again"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = { check() }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(tr("Проверить", "Check"))
            }
            if (misses >= FILL_MISSES_BEFORE_REVEAL) {
                OutlinedButton(
                    onClick = { revealed = true; solved = true; wrong = false; onSolved() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("Дальше", "Next"))
                }
            }
        }
    }
}

/** "sort": arrange the items with up/down arrows. The right order advances the lesson. */
@Composable
fun SortCardView(card: DrivenUiCard.SortCard, onSolved: () -> Unit) {
    // rememberSaveable: the arrangement and being solved survive the item being recycled in LazyColumn and a
    // rotation. remember used to reset the card to unsolved, and solving it again called onSolved ONE MORE
    // TIME — the lesson cursor jumped an extra step. onSolved is called only on the solved=false→true
    // transition, so a restored solved=true does not advance again.
    // The order is stored as indexes: duplicate strings in items do not confuse the restore.
    val order = rememberSaveable(
        card,
        saver = listSaver<SnapshotStateList<Int>, Int>(
            save = { it.toList() },
            restore = { it.toMutableStateList() },
        ),
    ) { card.items.indices.toList().toMutableStateList() }
    // correct must be a full permutation of the items' indexes. If it is broken (out of range, duplicates,
    // the wrong length — a frequent defect of an LLM card or an authoring mistake in a lesson), we degrade
    // to the original order so the step stays solvable with "Check" instead of getting stuck forever.
    val target = remember(card) {
        val n = card.items.size
        val isPermutation = card.correct.size == n && card.correct.toSet() == (0 until n).toSet()
        if (isPermutation) card.correct.map { card.items[it] } else card.items
    }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var wrong by remember(card) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.instruction.isNotBlank()) {
            Text(card.instruction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        order.forEachIndexed { i, itemIndex ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            ) {
                Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(8.dp))
                Text(card.items[itemIndex], style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (!solved) {
                    IconButton(onClick = {
                        if (i > 0) { val t = order[i - 1]; order[i - 1] = order[i]; order[i] = t; wrong = false }
                    }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, tr("Вверх", "Move up")) }
                    IconButton(onClick = {
                        if (i < order.size - 1) { val t = order[i + 1]; order[i + 1] = order[i]; order[i] = t; wrong = false }
                    }, enabled = i < order.size - 1) { Icon(Icons.Default.KeyboardArrowDown, tr("Вниз", "Move down")) }
                }
            }
        }
        if (solved) {
            if (card.explain.isNotBlank()) {
                Text("✓ ${card.explain}", style = MaterialTheme.typography.bodySmall, color = CardSuccessGreen)
            }
        } else {
            if (wrong) {
                Text(tr("Пока не тот порядок — переставь и проверь снова", "Not the right order yet — rearrange and check again"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    // The displayed strings are compared (not the indexes): with duplicates in items any visually identical
                    // order counts — as before.
                    if (order.map { card.items[it] } == target) { solved = true; wrong = false; onSolved() } else wrong = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Проверить", "Check")) }
        }
    }
}

/** "match": match terms with definitions by tapping. A full correct match advances the lesson. */
@Composable
fun MatchCardView(card: DrivenUiCard.MatchCard, onSolved: () -> Unit) {
    val terms = card.pairs.map { it.term }
    // Definitions are shown in a shuffled (stable) order. It used to be a shift by 1 — the right definition was
    // almost always "the next one in the list", and the matching could be guessed.
    // avoidIdentity: the original order would give away all pairs at once.
    val displayDefOrder = remember(card) {
        val seed = card.pairs.fold(0) { acc, p -> (acc * 31 + p.term.hashCode()) * 31 + p.def.hashCode() }
        stableShuffledIndices(card.pairs.size, seed, avoidIdentity = true)
    }
    // rememberSaveable: the matching and being solved survive recycling/rotation (see SortCardView — the same
    // class of bug with a repeated onSolved).
    val assign = rememberSaveable(
        card,
        saver = listSaver<SnapshotStateMap<Int, Int>, Int>(
            save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
            restore = { flat ->
                mutableStateMapOf<Int, Int>().apply {
                    var i = 0
                    while (i + 1 < flat.size) {
                        put(flat[i], flat[i + 1])
                        i += 2
                    }
                }
            },
        ),
    ) { mutableStateMapOf() } // termIndex -> the original index of the pair (which is also the right term)
    var selectedTerm by remember(card) { mutableStateOf<Int?>(null) }
    var solved by rememberSaveable(card) { mutableStateOf(false) }
    var wrong by remember(card) { mutableStateOf(false) }
    val correctGreen = CardSuccessGreen

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.instruction.isNotBlank()) {
            Text(card.instruction, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Terms
        terms.forEachIndexed { ti, term ->
            val assignedDef = assign[ti]?.let { card.pairs[it].def }
            val isSel = selectedTerm == ti
            val border = when {
                solved && assign[ti] == ti -> correctGreen
                solved -> MaterialTheme.colorScheme.error
                isSel -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.5.dp, border, RoundedCornerShape(10.dp))
                    .clickable(enabled = !solved) { selectedTerm = if (isSel) null else ti }
                    .padding(12.dp),
            ) {
                Text(term, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(
                    text = assignedDef ?: tr("выбери →", "pick →"),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (assignedDef != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1.2f),
                )
            }
        }
        CardDivider()
        Text(tr("Определения:", "Definitions:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        // Definition buttons: a tap assigns it to the selected term
        displayDefOrder.forEach { di ->
            val used = assign.containsValue(di)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (used) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                    )
                    .clickable(enabled = !solved && selectedTerm != null) {
                        val t = selectedTerm
                        if (t != null) {
                            // remove this def from its previous term, then assign
                            assign.entries.firstOrNull { it.value == di }?.let { assign.remove(it.key) }
                            assign[t] = di
                            selectedTerm = null
                            wrong = false
                        }
                    }
                    .padding(12.dp),
            ) {
                Text(card.pairs[di].def, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (used) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
        if (solved) {
            Text(tr("✓ Верно!", "✓ Correct!"), style = MaterialTheme.typography.bodySmall, color = correctGreen)
        } else {
            if (wrong) {
                Text(tr("Не всё совпало — поправь пары", "Not all pairs match — fix them"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = {
                    if (assign.size == terms.size && assign.all { it.key == it.value }) { solved = true; wrong = false; onSolved() } else wrong = true
                },
                enabled = assign.size == terms.size,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Проверить", "Check")) }
        }
    }
}

/** "scenario": a situation + one question with options. The answer advances the lesson. */
@Composable
fun ScenarioCardView(card: DrivenUiCard.ScenarioCard, onSolved: () -> Unit) {
    // rememberSaveable: the answer survives recycling/rotation — a repeated tap on a "reset" card used to call
    // onSolved ONE MORE TIME and push the lesson an extra step. -1 = not answered yet (the autosaver does
    // not save Int?). The ORIGINAL option index is stored — checking against answerIndex and restoring do
    // not depend on the display order.
    var selected by rememberSaveable(card) { mutableStateOf(-1) }
    // The options are in a shuffled (stable) order: in the authored JSON the right one is always first.
    val displayOrder = remember(card) {
        val seed = (card.situation.hashCode() * 31 + card.question.hashCode()) * 31 +
            card.options.fold(0) { acc, o -> acc * 31 + o.hashCode() }
        stableShuffledIndices(card.options.size, seed)
    }
    val correctGreen = CardSuccessGreen

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (card.situation.isNotBlank()) {
            Text(
                text = card.situation,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        if (card.question.isNotBlank()) {
            Text(card.question, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        displayOrder.forEach { i ->
            val option = card.options[i]
            val answered = selected >= 0
            // answerIndex < 0 — the right option is unknown (not set/broken): nothing is highlighted as "right/wrong",
            // so as not to mislead with a false key.
            val hasKey = card.answerIndex >= 0
            val isCorrect = hasKey && i == card.answerIndex
            val isPicked = selected == i
            val border = when {
                !answered -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                isCorrect -> correctGreen
                hasKey && isPicked -> MaterialTheme.colorScheme.error
                isPicked -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.5.dp, border, RoundedCornerShape(12.dp))
                    .clickable(enabled = selected < 0) {
                        selected = i
                        // Answered — move on regardless of correctness: the right option + the explanation are visible.
                        onSolved()
                    }
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(option, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                when {
                    answered && isCorrect -> Icon(Icons.Default.Check, null, tint = correctGreen)
                    answered && hasKey && isPicked -> Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (selected >= 0 && card.explain.isNotBlank()) {
            Text(card.explain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
