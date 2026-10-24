package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Идея агента: что написать в карточке и что ломается без инструментов и без памяти именно у этой задачи. */
private class BlueprintIdea(
    val chip: String,
    val title: String,
    val goal: String,
    val tools: String,
    val memory: String,
    val noTools: String,
    val noMemory: String,
)

private val IDEAS = listOf(
    BlueprintIdea(
        "Бюджет", "следить за бюджетом", "Учёт трат и лимиты", "база, уведомления", "категории, лимиты",
        "нужны база трат и напоминания о лимитах, а инструментов нет",
        "без памяти не помнит лимиты и прошлые траты",
    ),
    BlueprintIdea(
        "Слова", "учить слова", "10 новых слов в день", "карточки, уведомления", "что уже выучено",
        "нужны напоминания повторить слова, а инструментов нет",
        "без памяти не знает, какие слова ты уже выучил",
    ),
    BlueprintIdea(
        "Тренировки", "планировать тренировки", "План на неделю", "календарь, уведомления", "прогресс, самочувствие",
        "нужны календарь и напоминания, а инструментов нет",
        "программа на месяцы, а без памяти — каждую неделю с нуля",
    ),
)

/** Пять вопросов каркаса из урока. Первый вариант цели у каждой идеи свой — подставляется в коде. */
private class BlueprintQuestion(val title: String, val ask: String, val options: List<String>)

private val QUESTIONS = listOf(
    BlueprintQuestion("Цель", "что делает агент и как понять, что получилось?", listOf("", "Помогать во всём")),
    BlueprintQuestion("Инструменты", "до чего агенту нужно дотянуться?", listOf("Никаких", "Пара нужных", "Всё подряд")),
    BlueprintQuestion("Паттерн", "рельсы, водитель или их комбинация?", listOf("Воркфлоу", "ReAct-агент", "Рой агентов")),
    BlueprintQuestion("Память", "что помнить между сессиями?", listOf("Без памяти", "Только нужное", "Хранить всё")),
    BlueprintQuestion("Проверка", "как узнать, что после правки стало лучше?", listOf("На глазок", "10 типовых задач")),
)

private fun specValue(q: Int, a: Int, idea: BlueprintIdea): String = when (q) {
    0 -> if (a == 0) idea.goal else "помогать во всём"
    1 -> listOf("нет", idea.tools, "всё подряд, вперемешку")[a]
    2 -> listOf("воркфлоу", "агент с циклом ReAct", "рой агентов")[a]
    3 -> listOf("ничего", idea.memory, "всё подряд")[a]
    else -> listOf("на глазок", "10 типовых задач")[a]
}

/** Несостыковка ответа с задачей — или null, если ответ годится. */
private fun flagFor(q: Int, a: Int, idea: BlueprintIdea): String? = when {
    q == 0 && a == 1 -> "размытая цель — остальным решениям не на что опереться"
    q == 1 && a == 0 -> idea.noTools
    q == 1 && a == 2 -> "гора инструментов вперемешку — модель путает, что вызвать"
    q == 2 && a == 2 -> "рой ради одной задачи — дорого и сложно, хватит попроще"
    q == 3 && a == 0 -> idea.noMemory
    q == 3 && a == 2 -> "всё из контекста улетает провайдеру — храни только нужное"
    q == 4 && a == 0 -> "без набора задач не узнаешь, помогла ли правка"
    else -> null
}

/**
 * «Каркас агента»: пользователь берёт идею и по очереди отвечает на пять вопросов чипами — карточка
 * агента заполняется на глазах. Ответ, который не вяжется с задачей (напоминания без инструментов,
 * долгая программа без памяти), сразу помечается. Пять согласованных ответов — и идея стала проектом.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BlueprintScene() {
    var ideaIndex by rememberSaveable { mutableIntStateOf(0) }
    var answers by rememberSaveable { mutableStateOf(List(QUESTIONS.size) { -1 }) }
    var current by rememberSaveable { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val idea = IDEAS[ideaIndex]
    val flags = answers.withIndex().mapNotNull { (q, a) ->
        if (a < 0) null else flagFor(q, a, idea)?.let { "${QUESTIONS[q].title}: $it" }
    }
    val answered = answers.count { it >= 0 }
    val ready = answered == QUESTIONS.size && flags.isEmpty()
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.animateContentSize()) {
        Text("Собери агента, который будет…", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IDEAS.forEachIndexed { index, item ->
                FilterChip(selected = ideaIndex == index, onClick = { ideaIndex = index }, label = { Text(item.chip) })
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .padding(8.dp),
        ) {
            Text(
                "Агент: ${idea.title}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = SceneInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
            )
            QUESTIONS.forEachIndexed { q, question ->
                val a = answers[q]
                SpecRow(
                    title = question.title,
                    value = if (a < 0) null else specValue(q, a, idea),
                    flagged = a >= 0 && flagFor(q, a, idea) != null,
                    active = q == current,
                    onClick = { current = q },
                )
            }
        }
        val question = QUESTIONS[current]
        Text(
            "Вопрос ${current + 1} из ${QUESTIONS.size} — ${question.ask}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
        )
        // Место под два ряда чипов заранее: у вопросов разное число вариантов, и без запаса всё ниже прыгало бы.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
            question.options.forEachIndexed { i, label ->
                FilterChip(
                    selected = answers[current] == i,
                    onClick = {
                        val updated = answers.toMutableList().also { it[current] = i }
                        answers = updated
                        // Пауза, чтобы выбор успели увидеть, — и к первому вопросу без ответа.
                        scope.launch {
                            delay(350)
                            updated.indexOfFirst { it < 0 }.takeIf { it >= 0 }?.let { current = it }
                        }
                    },
                    label = { Text(if (current == 0 && i == 0) idea.goal else label) },
                )
            }
        }
        flags.forEach {
            Text("! $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = when {
                    answered < QUESTIONS.size -> "Отвечено $answered из ${QUESTIONS.size} — карточка заполняется"
                    flags.isNotEmpty() -> "Несостыковок: ${flags.size}. Нажми на строку карточки и поправь"
                    else -> "Пять ответов — и идея стала агентом, которого можно собрать"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    ready -> primary
                    answered == QUESTIONS.size -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
                minLines = 2,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { answers = List(QUESTIONS.size) { -1 }; current = 0 }, enabled = answered > 0) {
                Text("Сначала")
            }
        }
    }
}

@Composable
private fun SpecRow(title: String, value: String?, flagged: Boolean, active: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val background by animateColorAsState(if (active) primary.copy(alpha = 0.12f) else Color.Transparent, label = "specRow")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SceneInk, modifier = Modifier.width(96.dp))
        Text(
            text = value ?: "?",
            style = MaterialTheme.typography.bodyMedium,
            color = if (value == null) SceneInk.copy(alpha = 0.35f) else SceneInk,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Отметка справа: пусто — ещё не ответил, галочка — годится, «!» — не вяжется с задачей.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (value == null) Color.Transparent else if (flagged) error else primary),
        ) {
            if (value != null) {
                Text(
                    text = if (flagged) "!" else "✓",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (flagged) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}
