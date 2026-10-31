package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr

/** A model answer: the text, what the check will show (true, made up or an honest "not sure") and the caption for the result. */
private class ModelClaim(val text: String, val verdict: String, val truthful: Boolean, val note: String)

// As in the lesson: a date, a quote and a link to an article. The first two are real facts, the article is made up for the example.
// Getters, not stored lists: tr() has to run when the scene is drawn, so a language switch is seen at once.
private val CLAIMS: List<ModelClaim>
    get() = listOf(
        ModelClaim(
            tr("Гагарин полетел в космос 12 апреля 1961 года.", "Yuri Gagarin flew into space on 12 April 1961."),
            tr("Правда", "True"), true, tr("Дата сходится с энциклопедией", "The date matches the encyclopedia"),
        ),
        ModelClaim(
            tr("Перед стартом Гагарин сказал: «Поехали!»", "Before launch Gagarin said: “Let's go!”"),
            tr("Правда", "True"), true, tr("Есть в записи переговоров", "It's in the radio recording"),
        ),
        ModelClaim(
            tr(
                "Подробнее — в статье «Тайны первой орбиты», журнал «Космос и люди», 1987.",
                "More in the article “Secrets of the First Orbit,” Space and People magazine, 1987.",
            ),
            tr("Выдумка", "Made up"), false, tr("Такой статьи нет — ссылку сочинили", "There is no such article — the reference was invented"),
        ),
    )
private val HONEST_CLAIM: ModelClaim
    get() = ModelClaim(
        tr("Точную статью не назову — не уверен. Лучше проверь в источнике.", "I can't name the exact article — I'm not sure. Better check the source."),
        tr("Честно", "Honest"), true, tr("Сомнение вместо выдумки", "Doubt instead of invention"),
    )
private const val INVENTED = 2
private const val SURE = 0.95f
private const val UNSURE = 0.3f

/**
 * "Confident and wrong": three model answers sound equally confident — the tone scale is full for all of them.
 * The user checks each: two are confirmed, the third is made up. A toggle adds "if you are not sure, say so" to
 * the request, and the fabrication turns into an honest doubt. A confident tone is not a sign of truth.
 */
@Composable
internal fun ConfidentWrongScene() {
    var checked by rememberSaveable { mutableIntStateOf(0) }
    var allowDoubt by rememberSaveable { mutableStateOf(false) }
    val checkedCount = Integer.bitCount(checked)
    val allChecked = checkedCount == CLAIMS.size

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Три ответа модели — тон у всех одинаково уверенный. Где выдумка?", "Three model answers, all in an equally confident tone. Which one is made up?"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("Дописать в запрос: «если не уверен — так и скажи»", "Add to the request: “if you're not sure, say so”"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = allowDoubt,
                onCheckedChange = {
                    allowDoubt = it
                    checked = checked and (1 shl INVENTED).inv() // the third answer changed — its check starts over
                },
            )
        }
        CLAIMS.forEachIndexed { index, claim ->
            val honest = index == INVENTED && allowDoubt
            ClaimCard(
                claim = if (honest) HONEST_CLAIM else claim,
                confidence = if (honest) UNSURE else SURE,
                isChecked = checked and (1 shl index) != 0,
                onCheck = { checked = checked or (1 shl index) },
            )
        }
        Text(
            text = when {
                checkedCount == 0 -> tr("Угадай по тону, где выдумка, — а потом проверь все три", "Guess by the tone which one is made up — then check all three")
                !allChecked -> tr("Проверено $checkedCount из ${CLAIMS.size}", "Checked $checkedCount of ${CLAIMS.size}")
                allowDoubt -> tr("С разрешением сомневаться модель сказала «не уверен» вместо выдумки", "With permission to doubt, the model said “not sure” instead of inventing")
                else -> tr("Тон одинаково уверенный — правда это или нет, видно только после проверки", "The tone is equally confident — only a check shows whether it's true")
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
        )
        TextButton(onClick = { checked = 0 }, enabled = checkedCount > 0, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(tr("Сначала", "Start over"))
        }
    }
}

@Composable
private fun ClaimCard(claim: ModelClaim, confidence: Float, isChecked: Boolean, onCheck: () -> Unit) {
    val accent = if (claim.truthful) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val shownConfidence by animateFloatAsState(confidence, tween(400), label = "confidence")
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(2.dp, if (isChecked) accent else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
    ) {
        Text(claim.text, color = SceneInk, style = MaterialTheme.typography.bodyMedium, minLines = 2, modifier = Modifier.padding(end = 6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("уверенность", "confidence"), color = SceneInk.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            // The truth and the fabrication have a scale of the same color: they cannot be told apart by it — that is the whole trick.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SceneInk.copy(alpha = 0.1f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(shownConfidence)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(SceneInk.copy(alpha = 0.55f)),
                )
            }
            // The button and the verdict share one fixed-size spot — the card does not change under the finger.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(112.dp).height(40.dp)) {
                if (isChecked) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (claim.truthful) Icons.Default.Check else Icons.Default.Close,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(claim.verdict, color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = onCheck) { Text(tr("Проверить", "Check")) }
                }
            }
        }
        Text(
            text = if (isChecked) claim.note else " ",
            color = if (isChecked) accent else SceneInk,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}
