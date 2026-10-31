package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import java.util.Locale

/** A skill: the catalog line (level 1), the instruction via load_skill (level 2) and the data read with a tool (level 3). */
private class Skill(
    val id: String,
    private val nameRu: String, private val nameEn: String,
    private val briefRu: String, private val briefEn: String,
    private val instructionRu: String, private val instructionEn: String,
    private val resourceRu: String, private val resourceEn: String,
    val tokens: Int,
) {
    // Picked at display time: SKILLS is built once, and a tr() in its initializer would freeze the language.
    val name: String get() = tr(nameRu, nameEn)
    val brief: String get() = tr(briefRu, briefEn)
    val instruction: String get() = tr(instructionRu, instructionEn)
    val resource: String get() = tr(resourceRu, resourceEn)
}

// The same packs as in the app. The sizes in tokens are an example for clarity, not a measurement.
private val SKILLS = listOf(
    Skill(
        "budget", "Бюджет", "Budget", "учёт трат", "tracking spending",
        "Каждую трату — фактом в память: дата, сумма, на что. Итог за месяц — карточкой.",
        "Save each expense as a fact in memory: date, amount, what for. Show the monthly total as a card.",
        "траты за месяц", "this month's expenses", 500,
    ),
    Skill(
        "diary", "Дневник", "Diary", "записи дня", "daily entries",
        "Одна запись в день с датой. Не толкуй и не советуй без просьбы — это пространство человека.",
        "One entry a day, with the date. Don't interpret or advise unless asked. This is the person's own space.",
        "записи недели", "this week's entries", 800,
    ),
    Skill(
        "fitness", "Фитнес", "Fitness", "тренировки и шаги", "workouts and steps",
        "График занятий — будильником, план — фактом в память. Шаги бери с шагомера.",
        "Set the workout schedule as alarms, save the plan as a fact in memory. Take steps from the step counter.",
        "шаги недели", "this week's steps", 1600,
    ),
    Skill(
        "languages", "Языки", "Languages", "слова и повторения", "words and reviews",
        "Слова — в колоду с интервальными повторениями, по одному за раз; ошибки — снова в повтор.",
        "Put words in a spaced-repetition deck, one at a time; send mistakes back for review.",
        "колода дня", "today's deck", 2100,
    ),
)

private const val CATALOG_LINE = 40
private const val RESOURCE_TOKENS = 400
private const val WINDOW = 5000
private const val ALL = 0b1111

private fun fmt(n: Int) = String.format(Locale.forLanguageTag(tr("ru", "en")), "%,d", n)
private fun Int.has(i: Int) = (this and (1 shl i)) != 0

/**
 * "Skills on demand": only the catalog always sits in the window — four lines, almost free. A tap on a skill
 * loads its instruction, inside it the data can be read further — the window scale grows bit by bit. "Load
 * everything at once" overflows the window: it shows why skills are disclosed gradually.
 */
@Composable
internal fun SkillLevelsScene() {
    var loaded by rememberSaveable { mutableIntStateOf(0) } // bits: whose instructions are in the window (level 2)
    var read by rememberSaveable { mutableIntStateOf(0) } // bits: whose data has been read (level 3)
    val used = SKILLS.size * CATALOG_LINE +
        SKILLS.indices.sumOf { (if (loaded.has(it)) SKILLS[it].tokens else 0) + (if (read.has(it)) RESOURCE_TOKENS else 0) }
    val over = used > WINDOW
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    // The scale and the buttons are on top: expanded skills grow downwards and do not move anything from under the finger.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Задача: «хочу записывать траты». Тапни нужный навык.", "Task: “I want to track my spending.” Tap the right skill."), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val fill by animateFloatAsState((used.toFloat() / WINDOW).coerceAtMost(1f), tween(500), label = "skillMeter")
        val barColor by animateColorAsState(if (over) error else primary, tween(400), label = "skillMeterColor")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Окно (пример)", "Window (example)"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${fmt(used)} / ${fmt(WINDOW)}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = if (over) error else Color.Unspecified)
        }
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(primary.copy(alpha = 0.12f))) {
            Box(Modifier.fillMaxWidth(fill.coerceAtLeast(0.01f)).height(14.dp).clip(RoundedCornerShape(7.dp)).background(barColor))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (over) error.copy(alpha = 0.12f) else Color.White)
                .padding(12.dp),
        ) {
            Text(
                text = when {
                    over -> tr(
                        "Не влезает: окно забито тем, что сейчас не нужно. Поэтому навыки грузят по требованию.",
                        "It doesn't fit: the window is full of things not needed right now. That's why skills are loaded on demand.",
                    )
                    loaded == 0 -> tr(
                        "В окне только каталог — четыре строки. Агент знает, что умеет, почти даром.",
                        "Only the catalog is in the window: four lines. The agent knows what it can do, almost for free.",
                    )
                    else -> tr(
                        "В окне только нужное. Остальные навыки так и лежат одной строкой в каталоге.",
                        "Only what's needed is in the window. The other skills stay as one line each in the catalog.",
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (over || loaded != 0) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 3,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { loaded = ALL; read = ALL }, enabled = !over) { Text(tr("Загрузить всё сразу", "Load everything at once")) }
            Box(Modifier.weight(1f))
            TextButton(onClick = { loaded = 0; read = 0 }, enabled = loaded != 0) { Text(tr("Сначала", "Start over")) }
        }
        Text(tr("Уровень 1 — каталог, всегда в промпте:", "Level 1: the catalog, always in the prompt:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        SKILLS.forEachIndexed { index, skill ->
            SkillRow(
                skill = skill,
                loaded = loaded.has(index),
                read = read.has(index),
                onLoad = { loaded = loaded or (1 shl index) },
                onRead = { read = read or (1 shl index) },
            )
        }
    }
}

@Composable
private fun SkillRow(skill: Skill, loaded: Boolean, read: Boolean, onLoad: () -> Unit, onRead: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val border by animateColorAsState(if (loaded) primary else Color.Transparent, tween(300), label = "skillBorder")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(2.dp, border, RoundedCornerShape(12.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !loaded, onClick = onLoad)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(skill.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = SceneInk)
            Text(" — ${skill.brief}", style = MaterialTheme.typography.bodyMedium, color = SceneInk.copy(alpha = 0.7f), maxLines = 1, modifier = Modifier.weight(1f))
            Text("$CATALOG_LINE ${tr("ток.", "tokens")}", style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f), textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.widthIn(min = 56.dp))
        }
        AnimatedVisibility(visible = loaded, enter = fadeIn() + expandVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)) {
                Text(
                    text = tr("Уровень 2", "Level 2") + " · load_skill(\"${skill.id}\") · +${fmt(skill.tokens)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = primary,
                )
                Text(skill.instruction, style = MaterialTheme.typography.bodySmall, color = SceneInk)
                // Level 3: a skill's data is read with an ordinary tool — and only by a button, when it comes to that.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                    Text(tr("Уровень 3", "Level 3") + " ·", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = primary)
                    if (read) {
                        Text(tr(" прочитано «${skill.resource}» · +$RESOURCE_TOKENS", " read “${skill.resource}” · +$RESOURCE_TOKENS"), style = MaterialTheme.typography.labelMedium, color = SceneInk)
                    } else {
                        TextButton(onClick = onRead) { Text(tr("Прочитать «${skill.resource}»", "Read “${skill.resource}”"), maxLines = 1) }
                    }
                }
            }
        }
    }
}
