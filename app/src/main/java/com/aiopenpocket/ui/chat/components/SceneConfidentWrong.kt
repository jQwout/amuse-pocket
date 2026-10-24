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

/** Ответ модели: текст, что покажет проверка (правда, выдумка или честное «не уверен») и подпись к итогу. */
private class ModelClaim(val text: String, val verdict: String, val truthful: Boolean, val note: String)

// Как в уроке: дата, цитата и ссылка на статью. Первые две — настоящие факты, статья выдумана для примера.
private val CLAIMS = listOf(
    ModelClaim("Гагарин полетел в космос 12 апреля 1961 года.", "Правда", true, "Дата сходится с энциклопедией"),
    ModelClaim("Перед стартом Гагарин сказал: «Поехали!»", "Правда", true, "Есть в записи переговоров"),
    ModelClaim("Подробнее — в статье «Тайны первой орбиты», журнал «Космос и люди», 1987.", "Выдумка", false, "Такой статьи нет — ссылку сочинили"),
)
private val HONEST_CLAIM = ModelClaim("Точную статью не назову — не уверен. Лучше проверь в источнике.", "Честно", true, "Сомнение вместо выдумки")
private const val INVENTED = 2
private const val SURE = 0.95f
private const val UNSURE = 0.3f

/**
 * «Уверенно и неверно»: три ответа модели звучат одинаково уверенно — шкала тона у всех полная.
 * Пользователь проверяет каждый: два подтверждаются, третий выдуман. Переключатель дописывает в запрос
 * «если не уверен — так и скажи», и выдумка сменяется честным сомнением. Уверенный тон — не признак правды.
 */
@Composable
internal fun ConfidentWrongScene() {
    var checked by rememberSaveable { mutableIntStateOf(0) }
    var allowDoubt by rememberSaveable { mutableStateOf(false) }
    val checkedCount = Integer.bitCount(checked)
    val allChecked = checkedCount == CLAIMS.size

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Три ответа модели — тон у всех одинаково уверенный. Где выдумка?", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Дописать в запрос: «если не уверен — так и скажи»", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(
                checked = allowDoubt,
                onCheckedChange = {
                    allowDoubt = it
                    checked = checked and (1 shl INVENTED).inv() // третий ответ стал другим — его проверка заново
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
                checkedCount == 0 -> "Угадай по тону, где выдумка, — а потом проверь все три"
                !allChecked -> "Проверено $checkedCount из ${CLAIMS.size}"
                allowDoubt -> "С разрешением сомневаться модель сказала «не уверен» вместо выдумки"
                else -> "Тон одинаково уверенный — правда это или нет, видно только после проверки"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            minLines = 2,
        )
        TextButton(onClick = { checked = 0 }, enabled = checkedCount > 0, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Сначала")
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
            Text("уверенность", color = SceneInk.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(6.dp))
            // Шкала одного цвета у правды и у выдумки: по ней их не различить — в этом весь фокус.
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
            // Кнопка и вердикт делят одно место фиксированного размера — карточка не меняется под пальцем.
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
                    TextButton(onClick = onCheck) { Text("Проверить") }
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
