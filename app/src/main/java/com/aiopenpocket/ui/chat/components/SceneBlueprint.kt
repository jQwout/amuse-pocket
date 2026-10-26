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
import com.aiopenpocket.i18n.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * An agent idea: what to write in the card and what breaks without tools and without memory for this
 * particular task. Texts come in ru/en pairs, the language is picked by a getter at display time: the list is
 * initialized once.
 */
private class BlueprintIdea(
    private val chipRu: String,
    private val chipEn: String,
    private val titleRu: String,
    private val titleEn: String,
    private val goalRu: String,
    private val goalEn: String,
    private val toolsRu: String,
    private val toolsEn: String,
    private val memoryRu: String,
    private val memoryEn: String,
    private val noToolsRu: String,
    private val noToolsEn: String,
    private val noMemoryRu: String,
    private val noMemoryEn: String,
) {
    val chip: String get() = tr(chipRu, chipEn)
    val title: String get() = tr(titleRu, titleEn)
    val goal: String get() = tr(goalRu, goalEn)
    val tools: String get() = tr(toolsRu, toolsEn)
    val memory: String get() = tr(memoryRu, memoryEn)
    val noTools: String get() = tr(noToolsRu, noToolsEn)
    val noMemory: String get() = tr(noMemoryRu, noMemoryEn)
}

private val IDEAS = listOf(
    BlueprintIdea(
        chipRu = "Бюджет", chipEn = "Budget",
        titleRu = "следить за бюджетом", titleEn = "track a budget",
        goalRu = "Учёт трат и лимиты", goalEn = "Track spending and limits",
        toolsRu = "база, уведомления", toolsEn = "database, notifications",
        memoryRu = "категории, лимиты", memoryEn = "categories, limits",
        noToolsRu = "нужны база трат и напоминания о лимитах, а инструментов нет",
        noToolsEn = "needs a spending database and limit reminders, but has no tools",
        noMemoryRu = "без памяти не помнит лимиты и прошлые траты",
        noMemoryEn = "without memory it forgets the limits and past spending",
    ),
    BlueprintIdea(
        chipRu = "Слова", chipEn = "Words",
        titleRu = "учить слова", titleEn = "learn new words",
        goalRu = "10 новых слов в день", goalEn = "10 new words a day",
        toolsRu = "карточки, уведомления", toolsEn = "flashcards, notifications",
        memoryRu = "что уже выучено", memoryEn = "what's already learned",
        noToolsRu = "нужны напоминания повторить слова, а инструментов нет",
        noToolsEn = "needs reminders to review words, but has no tools",
        noMemoryRu = "без памяти не знает, какие слова ты уже выучил",
        noMemoryEn = "without memory it doesn't know which words you've learned",
    ),
    BlueprintIdea(
        chipRu = "Тренировки", chipEn = "Workouts",
        titleRu = "планировать тренировки", titleEn = "plan workouts",
        goalRu = "План на неделю", goalEn = "A weekly plan",
        toolsRu = "календарь, уведомления", toolsEn = "calendar, notifications",
        memoryRu = "прогресс, самочувствие", memoryEn = "progress, how you feel",
        noToolsRu = "нужны календарь и напоминания, а инструментов нет",
        noToolsEn = "needs a calendar and reminders, but has no tools",
        noMemoryRu = "программа на месяцы, а без памяти — каждую неделю с нуля",
        noMemoryEn = "a months-long program, but without memory it starts from scratch every week",
    ),
)

/**
 * The lesson's five blueprint questions. The first goal option is different for each idea — it is filled in
 * in code. Texts come in ru/en pairs (the options as two lists of the same length), the language is picked at
 * display time.
 */
private class BlueprintQuestion(
    private val titleRu: String,
    private val titleEn: String,
    private val askRu: String,
    private val askEn: String,
    private val optionsRu: List<String>,
    private val optionsEn: List<String>,
) {
    val title: String get() = tr(titleRu, titleEn)
    val ask: String get() = tr(askRu, askEn)
    val options: List<String> get() = optionsRu.zip(optionsEn) { ru, en -> tr(ru, en) }
}

private val QUESTIONS = listOf(
    BlueprintQuestion(
        "Цель", "Goal",
        "что делает агент и как понять, что получилось?", "what does the agent do, and how do you know it worked?",
        listOf("", "Помогать во всём"), listOf("", "Help with everything"),
    ),
    BlueprintQuestion(
        "Инструменты", "Tools",
        "до чего агенту нужно дотянуться?", "what does the agent need to reach?",
        listOf("Никаких", "Пара нужных", "Всё подряд"), listOf("None", "A couple of key ones", "Everything"),
    ),
    BlueprintQuestion(
        "Паттерн", "Pattern",
        "рельсы, водитель или их комбинация?", "rails, a driver or a mix of both?",
        listOf("Воркфлоу", "ReAct-агент", "Рой агентов"), listOf("Workflow", "ReAct agent", "Agent swarm"),
    ),
    BlueprintQuestion(
        "Память", "Memory",
        "что помнить между сессиями?", "what to remember between sessions?",
        listOf("Без памяти", "Только нужное", "Хранить всё"), listOf("No memory", "Only what's needed", "Keep everything"),
    ),
    BlueprintQuestion(
        "Проверка", "Testing",
        "как узнать, что после правки стало лучше?", "how do you know a change made things better?",
        listOf("На глазок", "10 типовых задач"), listOf("Eyeball it", "10 typical tasks"),
    ),
)

private fun specValue(q: Int, a: Int, idea: BlueprintIdea): String = when (q) {
    0 -> if (a == 0) idea.goal else tr("помогать во всём", "help with everything")
    1 -> listOf(tr("нет", "none"), idea.tools, tr("всё подряд, вперемешку", "everything, all mixed together"))[a]
    2 -> listOf(tr("воркфлоу", "workflow"), tr("агент с циклом ReAct", "agent with a ReAct loop"), tr("рой агентов", "agent swarm"))[a]
    3 -> listOf(tr("ничего", "nothing"), idea.memory, tr("всё подряд", "everything"))[a]
    else -> listOf(tr("на глазок", "by eye"), tr("10 типовых задач", "10 typical tasks"))[a]
}

/** The mismatch between an answer and the task — or null if the answer fits. */
private fun flagFor(q: Int, a: Int, idea: BlueprintIdea): String? = when {
    q == 0 && a == 1 -> tr("размытая цель — остальным решениям не на что опереться", "vague goal — the other decisions have nothing to stand on")
    q == 1 && a == 0 -> idea.noTools
    q == 1 && a == 2 -> tr("гора инструментов вперемешку — модель путает, что вызвать", "a jumbled pile of tools — the model mixes up what to call")
    q == 2 && a == 2 -> tr("рой ради одной задачи — дорого и сложно, хватит попроще", "a swarm for one task — costly and complex, something simpler will do")
    q == 3 && a == 0 -> idea.noMemory
    q == 3 && a == 2 -> tr("всё из контекста улетает провайдеру — храни только нужное", "everything in the context goes to the provider — keep only what's needed")
    q == 4 && a == 0 -> tr("без набора задач не узнаешь, помогла ли правка", "without a task set you won't know if a change helped")
    else -> null
}

/**
 * "Agent blueprint": the user takes an idea and answers five questions in turn with chips — the agent's card
 * fills in before their eyes. An answer that does not fit the task (reminders without tools, a long program
 * without memory) is flagged right away. Five consistent answers — and the idea has become a project.
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
        Text(tr("Собери агента, который будет…", "Build an agent that will…"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                tr("Агент: ${idea.title}", "Agent: ${idea.title}"),
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
            tr("Вопрос ${current + 1} из ${QUESTIONS.size} — ${question.ask}", "Question ${current + 1} of ${QUESTIONS.size} — ${question.ask}"),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
        )
        // Space for two rows of chips in advance: the questions have different numbers of options, and without the margin everything below would jump.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
            question.options.forEachIndexed { i, label ->
                FilterChip(
                    selected = answers[current] == i,
                    onClick = {
                        val updated = answers.toMutableList().also { it[current] = i }
                        answers = updated
                        // A pause so the choice is seen — then on to the first unanswered question.
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
                    answered < QUESTIONS.size -> tr(
                        "Отвечено $answered из ${QUESTIONS.size} — карточка заполняется",
                        "Answered $answered of ${QUESTIONS.size} — the card is filling in",
                    )
                    flags.isNotEmpty() -> tr(
                        "Несостыковок: ${flags.size}. Нажми на строку карточки и поправь",
                        "Mismatches: ${flags.size}. Tap a row of the card to fix it",
                    )
                    else -> tr(
                        "Пять ответов — и идея стала агентом, которого можно собрать",
                        "Five answers — and the idea has become an agent you can build",
                    )
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
                Text(tr("Сначала", "Start over"))
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
        // The mark on the right: empty — not answered yet, a checkmark — fits, "!" — does not fit the task.
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
