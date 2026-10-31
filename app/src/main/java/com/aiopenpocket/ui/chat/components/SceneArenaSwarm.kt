package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import kotlin.math.roundToInt

/** An arena run: the answer, the judge's score (out of 10, illustrative), its verdict and at which share of the time the run finishes. */
private class ArenaRun(val answer: String, val score: Int, val verdict: String, val doneAt: Float)

// The lesson's example: three versions of a letter and a judge with the criteria "polite, short, precise".
// Getters, not vals: the text is picked in the interface language when it is shown, not once at class load.
private val ARENA_PROMPT: String
    get() = tr("письмо коллеге: встречу переносим на пятницу", "a note to a colleague: the meeting moves to Friday")
private val RUNS: List<ArenaRun>
    get() = listOf(
        ArenaRun(
            tr("Встречи в среду не будет. Перенесли на пятницу.", "No meeting on Wednesday. Moved to Friday."),
            5,
            tr("коротко, но сухо и без времени", "short, but dry and with no time"),
            0.55f,
        ),
        ArenaRun(
            tr(
                "Привет! Давай перенесём встречу на пятницу, на 11:00? Если неудобно — предложи своё время.",
                "Hi! Shall we move the meeting to Friday at 11:00? If that doesn't work, suggest a time.",
            ),
            9,
            tr("вежливо, коротко и с точным временем", "polite, short and with an exact time"),
            1f,
        ),
        ArenaRun(
            tr(
                "Добрый день! Пишу, чтобы сообщить, что в силу ряда обстоятельств, которые от нас, к сожалению, не зависят, мы вынуждены…",
                "Good afternoon! I am writing to let you know that, due to a number of circumstances which are unfortunately beyond our control, we are obliged to…",
            ),
            6,
            tr("вежливо, но длинно — мимо «коротко»", "polite, but long, so it misses “short”"),
            0.8f,
        ),
    )
private const val WINNER = 1

/** A swarm worker: which chunk it got, what it found in it and how long it reads (ms, for the animation). */
private class SwarmWorker(val reviews: String, val finding: String, val workMs: Int)

// The lesson's swarm example: 20 reviews, the orchestrator hands five each to four workers.
private val WORKERS: List<SwarmWorker>
    get() = listOf(
        SwarmWorker(tr("отзывы 1–5", "reviews 1–5"), tr("хвалят скорость", "praise the speed"), 1300),
        SwarmWorker(tr("отзывы 6–10", "reviews 6–10"), tr("путаются в меню", "get lost in the menu"), 1700),
        SwarmWorker(tr("отзывы 11–15", "reviews 11–15"), tr("просят тёмную тему", "ask for a dark theme"), 1100),
        SwarmWorker(tr("отзывы 16–20", "reviews 16–20"), tr("снова меню и скорость", "the menu and the speed again"), 1500),
    )
private val SUMMARY: String
    get() = tr(
        "Выжимка: хвалят скорость, путаются в меню, просят тёмную тему",
        "Summary: people praise the speed, get lost in the menu and ask for a dark theme",
    )
private const val SPLIT_MS = 500
private const val MERGE_MS = 600
private val SWARM_MS = SPLIT_MS + WORKERS.maxOf { it.workMs } + MERGE_MS
/** A single agent would read all 20 in a row: the same work, but without parallelism. */
private val SOLO_MS = WORKERS.sumOf { it.workMs }

/**
 * "Arena and swarm": two tabs. In the arena one request goes to three runs — they write different things,
 * and a judge picks the best one by criteria; a tap on a card shows what the judge said about it. In the
 * swarm the orchestrator cuts 20 reviews into four chunks, the workers read in parallel, and the results
 * merge into one summary.
 */
@Composable
internal fun ArenaSwarmScene() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text(tr("Арена", "Arena")) })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text(tr("Рой", "Swarm")) })
        }
        if (tab == 0) ArenaTab() else SwarmTab()
    }
}

@Composable
private fun ArenaTab() {
    // 0 — waiting, 1 — the runs are writing, 2 — the answers are ready, 3 — the judge is reading, 4 — a winner is picked.
    var stage by rememberSaveable { mutableIntStateOf(0) }
    var picked by rememberSaveable { mutableIntStateOf(WINNER) }
    val write = remember { Animatable(0f) }
    val judge = remember { Animatable(0f) }
    LaunchedEffect(stage) {
        when (stage) {
            0 -> { write.snapTo(0f); judge.snapTo(0f) }
            1 -> { write.animateTo(1f, tween(1600, easing = LinearEasing)); stage = 2 }
            3 -> { write.snapTo(1f); judge.animateTo(1f, tween(900)); stage = 4 }
            else -> { write.snapTo(1f); if (stage == 4) judge.snapTo(1f) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Один запрос — три прогона", "One request, three runs") + ": “$ARENA_PROMPT”", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        RUNS.forEachIndexed { index, run ->
            // Three runs write at once, but at different speeds — like parallel requests.
            val progress = (write.value / run.doneAt).coerceIn(0f, 1f)
            RunCard(
                number = index + 1,
                text = run.answer.take((run.answer.length * progress).roundToInt()),
                score = if (stage >= 3) (run.score * judge.value).roundToInt() else null,
                winner = stage == 4 && index == WINNER,
                selected = stage == 4 && index == picked,
                enabled = stage == 4,
                onClick = { picked = index },
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp),
        ) {
            Text(
                text = if (stage == 4) tr("Модель-судья · тапни карточку, чтобы сравнить", "Judge model · tap a card to compare") else tr("Модель-судья", "Judge model"),
                style = MaterialTheme.typography.labelMedium,
                color = SceneInk.copy(alpha = 0.6f),
            )
            Text(
                text = when {
                    stage < 3 -> tr("Ждёт ответы. Критерии: вежливо, коротко, точно", "Waiting for the answers. Criteria: polite, short, precise")
                    stage == 3 -> tr("Читает и сравнивает по критериям…", "Reading and comparing against the criteria…")
                    else -> "#${picked + 1}: ${RUNS[picked].score}/10 — ${RUNS[picked].verdict}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = SceneInk,
                minLines = 2,
            )
        }
        Button(
            onClick = {
                stage = when (stage) {
                    0 -> 1
                    2 -> 3
                    else -> 0
                }
                if (stage == 0) picked = WINNER
            },
            enabled = stage != 1 && stage != 3,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                listOf(
                    tr("Запустить 3 прогона", "Start 3 runs"),
                    tr("Прогоны пишут…", "The runs are writing…"),
                    tr("Позвать судью", "Call the judge"),
                    tr("Судья читает…", "The judge is reading…"),
                    tr("Сначала", "Start over"),
                )[stage],
            )
        }
        Text(
            text = when (stage) {
                0 -> tr(
                    "Запусти — и посмотри, как по-разному модель отвечает на один и тот же запрос",
                    "Start it and see how differently a model answers the very same request",
                )
                4 -> tr(
                    "Один прогон мог дать №1 — и ты бы не узнал. Тот же приём — прогоны и судья — меряет, стал ли агент лучше после правки",
                    "A single run could have given you #1, and you would never have known. The same trick, runs plus a judge, measures whether an agent got better after a change",
                )
                else -> tr("Ответы разные, хотя запрос один. Какой лучший? Пусть решит судья", "The answers differ, though the request is the same. Which is best? Let the judge decide")
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (stage == 4) FontWeight.SemiBold else FontWeight.Normal,
            minLines = 3,
        )
    }
}

@Composable
private fun RunCard(number: Int, text: String, score: Int?, winner: Boolean, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(if (winner) ScenePalette[2] else Color.White, tween(300), label = "runBg")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("#$number", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SceneInk, modifier = Modifier.width(30.dp))
        Text(
            text = text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall,
            color = SceneInk,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
            Text(score?.let { "$it/10" } ?: "—", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SceneInk)
            Text(if (winner) tr("лучший", "best") else " ", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = SceneInk)
        }
    }
}

@Composable
private fun SwarmTab() {
    var started by rememberSaveable { mutableStateOf(false) }
    val elapsed = remember { Animatable(0f) }
    LaunchedEffect(started) {
        if (!started) {
            elapsed.snapTo(0f)
            return@LaunchedEffect
        }
        elapsed.animateTo(SWARM_MS.toFloat(), tween(SWARM_MS, easing = LinearEasing))
    }
    val t = elapsed.value
    val done = t >= SWARM_MS
    val running = started && !done
    val solo = (t / SOLO_MS).coerceIn(0f, 1f)
    val primary = MaterialTheme.colorScheme.primary

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Задача: «изучи 20 отзывов и сделай выжимку»", "Task: “study 20 reviews and write a summary”"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ScenePalette[0]).padding(10.dp)) {
            Text(tr("Оркестратор", "Orchestrator"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = SceneInk)
            Text(
                text = when {
                    !started -> tr("держит все 20 отзывов", "holds all 20 reviews")
                    t < SPLIT_MS -> tr("режет на 4 куска по 5…", "cutting them into 4 pieces of 5…")
                    t < SWARM_MS - MERGE_MS -> tr("раздал куски — воркеры читают параллельно", "handed out the pieces; the workers read in parallel")
                    !done -> tr("сводит итоги воркеров…", "merging the workers' results…")
                    else -> tr("свёл итоги в одну выжимку", "merged the results into one summary")
                },
                style = MaterialTheme.typography.bodySmall,
                color = SceneInk,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        WORKERS.forEachIndexed { index, worker ->
            val progress = if (started) ((t - SPLIT_MS) / worker.workMs).coerceIn(0f, 1f) else 0f
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("W${index + 1}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = SceneInk, modifier = Modifier.width(30.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (started && t >= SPLIT_MS) worker.reviews else tr("ждёт свой кусок", "waiting for its piece"), style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f))
                    Text(if (progress >= 1f) worker.finding else " ", style = MaterialTheme.typography.bodySmall, color = SceneInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Bar(progress = progress, color = primary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Text(tr("Один агент подряд", "One agent in a row"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Bar(progress = solo, color = SceneInk.copy(alpha = 0.45f))
            Text("${(solo * 20).toInt()} " + tr("из", "of") + " 20", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.width(52.dp))
        }
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (done) ScenePalette[2] else Color.White).padding(10.dp)) {
            Text(
                text = if (done) SUMMARY else tr("Выжимка появится, когда все воркеры закончат", "The summary appears when all the workers finish"),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
                color = if (done) SceneInk else SceneInk.copy(alpha = 0.5f),
                minLines = 2,
            )
        }
        Button(onClick = { started = !started }, enabled = !running, modifier = Modifier.fillMaxWidth()) {
            Text(
                when {
                    !started -> tr("Запустить рой", "Start the swarm")
                    running -> tr("Воркеры читают…", "The workers are reading…")
                    else -> tr("Сначала", "Start over")
                },
            )
        }
        Text(
            text = if (done) {
                tr(
                    "Быстрее: каждый воркер держит в голове 5 отзывов, а не 20. Но запросов уже шесть — раздать, четыре воркера, свести",
                    "Faster: each worker keeps 5 reviews in mind, not 20. But that is already six requests: hand out, four workers, merge",
                )
            } else {
                tr(
                    "Один агент читал бы все 20 подряд. Запусти — и посмотри, кто успеет раньше",
                    "One agent would read all 20 in a row. Start it and see who finishes first",
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal,
            minLines = 3,
        )
    }
}

@Composable
private fun Bar(progress: Float, color: Color) {
    Box(modifier = Modifier.width(72.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f))) {
        Box(modifier = Modifier.fillMaxWidth(progress).height(8.dp).background(color))
    }
}
