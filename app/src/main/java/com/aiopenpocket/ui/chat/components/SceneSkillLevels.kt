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
import androidx.compose.foundation.layout.width
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
import java.util.Locale

/** Навык: строка каталога (уровень 1), инструкция по load_skill (уровень 2) и данные, которые читают инструментом (уровень 3). */
private class Skill(val id: String, val name: String, val brief: String, val instruction: String, val resource: String, val tokens: Int)

// Те же паки, что в приложении. Размеры в токенах — пример для наглядности, не замер.
private val SKILLS = listOf(
    Skill("budget", "Бюджет", "учёт трат", "Каждую трату — фактом в память: дата, сумма, на что. Итог за месяц — карточкой.", "траты за месяц", 500),
    Skill("diary", "Дневник", "записи дня", "Одна запись в день с датой. Не толкуй и не советуй без просьбы — это пространство человека.", "записи недели", 800),
    Skill("fitness", "Фитнес", "тренировки и шаги", "График занятий — будильником, план — фактом в память. Шаги бери с шагомера.", "шаги недели", 1600),
    Skill("languages", "Языки", "слова и повторения", "Слова — в колоду с интервальными повторениями, по одному за раз; ошибки — снова в повтор.", "колода дня", 2100),
)

private const val CATALOG_LINE = 40
private const val RESOURCE_TOKENS = 400
private const val WINDOW = 5000
private const val ALL = 0b1111

private fun fmt(n: Int) = String.format(Locale.forLanguageTag("ru"), "%,d", n)
private fun Int.has(i: Int) = (this and (1 shl i)) != 0

/**
 * «Навыки по требованию»: в окне всегда лежит только каталог — четыре строки почти даром. Тап по
 * навыку грузит его инструкцию, внутри можно дочитать данные — шкала окна растёт по кусочку.
 * «Загрузить всё сразу» переполняет окно: видно, почему навыки раскрывают постепенно.
 */
@Composable
internal fun SkillLevelsScene() {
    var loaded by rememberSaveable { mutableIntStateOf(0) } // биты: чьи инструкции в окне (уровень 2)
    var read by rememberSaveable { mutableIntStateOf(0) } // биты: чьи данные прочитаны (уровень 3)
    val used = SKILLS.size * CATALOG_LINE +
        SKILLS.indices.sumOf { (if (loaded.has(it)) SKILLS[it].tokens else 0) + (if (read.has(it)) RESOURCE_TOKENS else 0) }
    val over = used > WINDOW
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    // Шкала и кнопки — сверху: раскрытые навыки растут вниз и ничего не сдвигают из-под пальца.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Задача: «хочу записывать траты». Тапни нужный навык.", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val fill by animateFloatAsState((used.toFloat() / WINDOW).coerceAtMost(1f), tween(500), label = "skillMeter")
        val barColor by animateColorAsState(if (over) error else primary, tween(400), label = "skillMeterColor")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Окно (пример)", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
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
                    over -> "Не влезает: окно забито тем, что сейчас не нужно. Поэтому навыки грузят по требованию."
                    loaded == 0 -> "В окне только каталог — четыре строки. Агент знает, что умеет, почти даром."
                    else -> "В окне только нужное. Остальные навыки так и лежат одной строкой в каталоге."
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (over || loaded != 0) FontWeight.SemiBold else FontWeight.Normal,
                color = SceneInk,
                minLines = 3,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { loaded = ALL; read = ALL }, enabled = !over) { Text("Загрузить всё сразу") }
            Box(Modifier.weight(1f))
            TextButton(onClick = { loaded = 0; read = 0 }, enabled = loaded != 0) { Text("Сначала") }
        }
        Text("Уровень 1 — каталог, всегда в промпте:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
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
            Text("$CATALOG_LINE ток.", style = MaterialTheme.typography.labelMedium, color = SceneInk.copy(alpha = 0.6f), textAlign = TextAlign.End, modifier = Modifier.width(56.dp))
        }
        AnimatedVisibility(visible = loaded, enter = fadeIn() + expandVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp)) {
                Text(
                    text = "Уровень 2 · load_skill(\"${skill.id}\") · +${fmt(skill.tokens)}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = primary,
                )
                Text(skill.instruction, style = MaterialTheme.typography.bodySmall, color = SceneInk)
                // Уровень 3: данные навыка читаются обычным инструментом — и только по кнопке, когда до них дошло дело.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
                    Text("Уровень 3 ·", style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace), color = primary)
                    if (read) {
                        Text(" прочитано «${skill.resource}» · +$RESOURCE_TOKENS", style = MaterialTheme.typography.labelMedium, color = SceneInk)
                    } else {
                        TextButton(onClick = onRead) { Text("Прочитать «${skill.resource}»", maxLines = 1) }
                    }
                }
            }
        }
    }
}
