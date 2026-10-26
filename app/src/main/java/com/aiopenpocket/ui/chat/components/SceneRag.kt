package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// A small "knowledge base": an employee handbook, already cut into chunks.
private val CHUNKS = listOf(
    "Отчёт за квартал сдаём до 5-го числа следующего месяца.",
    "Кофемашина — на третьем этаже, капсулы у ресепшена.",
    "Отпуск — 28 дней в году, заявление подают за две недели.",
    "Пропуск выдаёт служба безопасности, кабинет 105.",
    "Больничный оформляют в первый же день болезни.",
    "Отчёты отправляют руководителю отдела на почту.",
)

/** A question, each chunk's similarity to it (illustrative — as embeddings would give) and the answer from the two best. */
private class RagQuestion(val text: String, val scores: List<Float>, val answer: String)

private val QUESTIONS = listOf(
    RagQuestion(
        "Когда сдавать отчёт?",
        listOf(0.92f, 0.08f, 0.21f, 0.10f, 0.18f, 0.74f),
        "До 5-го числа следующего месяца — и отправить руководителю отдела на почту.",
    ),
    RagQuestion(
        "Сколько дней отпуска?",
        listOf(0.15f, 0.05f, 0.94f, 0.07f, 0.33f, 0.09f),
        "28 дней в году, заявление — за две недели.",
    ),
    RagQuestion(
        "Где получить пропуск?",
        listOf(0.06f, 0.22f, 0.08f, 0.95f, 0.12f, 0.11f),
        "В службе безопасности, кабинет 105.",
    ),
)

private const val TOP_K = 2

/**
 * "RAG": the user picks a question, the base computes each chunk's similarity before your eyes, the two best go
 * into the package for the model — and only then does an answer built from them appear. First find, then answer.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RagScene() {
    var selected by rememberSaveable { mutableIntStateOf(-1) }
    // 0 — searching, 1 — the chunks are in the package, 2 — the answer is ready.
    var phase by remember { mutableIntStateOf(0) }
    val search = remember { Animatable(0f) }
    LaunchedEffect(selected) {
        if (selected < 0) return@LaunchedEffect
        phase = 0
        search.snapTo(0f)
        search.animateTo(1f, tween(900))
        phase = 1
        delay(700)
        phase = 2
    }
    val question = QUESTIONS.getOrNull(selected)
    val top = question?.scores?.withIndex()?.sortedByDescending { it.value }?.take(TOP_K)?.map { it.index }.orEmpty()
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
        Text("Спроси базу — памятку сотрудника:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QUESTIONS.forEachIndexed { index, q ->
                FilterChip(selected = selected == index, onClick = { selected = index }, label = { Text(q.text) })
            }
        }
        CHUNKS.forEachIndexed { index, chunk ->
            val score = (question?.scores?.get(index) ?: 0f) * search.value
            val picked = question != null && phase >= 1 && index in top
            ChunkRow(number = index + 1, text = chunk, score = score, picked = picked, accent = primary)
        }
        AnimatedVisibility(visible = question != null && phase >= 1, enter = fadeIn() + expandVertically()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, primary, RoundedCornerShape(12.dp))
                    .padding(10.dp),
            ) {
                Text("Пакет для модели: вопрос + куски ${top.joinToString(" и ") { "${it + 1}" }}", style = MaterialTheme.typography.labelMedium, color = primary, fontWeight = FontWeight.SemiBold)
                top.forEach { Text("· ${CHUNKS[it]}", style = MaterialTheme.typography.bodySmall, color = SceneInk) }
            }
        }
        AnimatedVisibility(visible = question != null && phase >= 2, enter = fadeIn()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(primary.copy(alpha = 0.14f))
                    .padding(12.dp),
            ) {
                Text("Ответ: ${question?.answer.orEmpty()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = SceneInk)
            }
        }
    }
}

@Composable
private fun ChunkRow(number: Int, text: String, score: Float, picked: Boolean, accent: Color) {
    val background by animateColorAsState(if (picked) ScenePalette[2] else Color.White, tween(300), label = "chunkBg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text("$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SceneInk, modifier = Modifier.width(18.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = SceneInk, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .padding(start = 8.dp)
                .width(46.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accent.copy(alpha = 0.12f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(score.coerceIn(0f, 1f))
                    .height(8.dp)
                    .background(accent),
            )
        }
    }
}
