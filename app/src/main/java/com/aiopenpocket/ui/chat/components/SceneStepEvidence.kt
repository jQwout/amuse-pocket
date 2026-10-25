package com.aiopenpocket.ui.chat.components

// @trace protsedurnye-animatsii-v-urokah-68b2bf

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Шаг плана и сохранённый результат, который движок примет как доказательство его критерия. */
private class PlanStep(val title: String, val evidence: String)

// План из практики урока: найти три статьи, выписать главные мысли, собрать их в один список.
private val PLAN = listOf(
    PlanStep("Найти три статьи по теме", "3 ссылки сохранены"),
    PlanStep("Выписать из каждой главную мысль", "3 выписки в заметке"),
    PlanStep("Собрать мысли в один список", "список из 3 пунктов"),
)

/**
 * Статусы шага — теми же словами, что на настоящей карточке плана в чате (PlanCard): ученик потом
 * увидит именно их. «Ожидает» — пока не выполнен предыдущий шаг.
 */
private enum class StepStatus(val label: String) { Waiting("Ожидает"), Ready("Готов к запуску"), Blocked("Заблокирован"), Verified("Завершён") }

private val ProvenGreen = Color(0xFF2E7D32)

private fun Int.hasStep(index: Int) = (this and (1 shl index)) != 0

/**
 * «Сделал — докажи»: пользователь играет агента и жмёт «Сдать» у шагов плана. Голое «готово» движок
 * не засчитывает — шаг блокируется, пока к нему не приложено доказательство. Следующий шаг открывается
 * только за проверенным, и план выполнен, лишь когда доказательство есть у каждого.
 */
@Composable
internal fun StepEvidenceScene() {
    // Битовые маски по шагам: приложено доказательство / проверен / заблокирован.
    var attached by rememberSaveable { mutableIntStateOf(0) }
    var verified by rememberSaveable { mutableIntStateOf(0) }
    var blocked by rememberSaveable { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf("Жми «Сдать» у шага 1 — посмотрим, поверит ли движок на слово") }
    var rejected by rememberSaveable { mutableStateOf(false) }
    val provenCount = verified.countOneBits()
    val error = MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ты — агент. План одобрен, выполняем по шагам", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        PLAN.forEachIndexed { index, step ->
            val bit = 1 shl index
            val status = when {
                verified.hasStep(index) -> StepStatus.Verified
                blocked.hasStep(index) -> StepStatus.Blocked
                index == 0 || verified.hasStep(index - 1) -> StepStatus.Ready
                else -> StepStatus.Waiting
            }
            StepCard(
                number = index + 1,
                step = step,
                status = status,
                attached = attached.hasStep(index),
                onAttach = {
                    attached = attached or bit
                    message = "Доказательство «${step.evidence}» приложено — теперь жми «Сдать»"
                    rejected = false
                },
                onDone = {
                    if (attached.hasStep(index)) {
                        verified = verified or bit
                        blocked = blocked and bit.inv()
                        rejected = false
                        message = if (index == PLAN.lastIndex) {
                            "План выполнен — каждый шаг с доказательством. На слово не верим"
                        } else {
                            "Шаг ${index + 1} завершён: критерий подтверждён. Открылся шаг ${index + 2}"
                        }
                    } else {
                        blocked = blocked or bit
                        rejected = true
                        // Настоящий движок сначала даёт шагу ещё попытку и блокирует после второй (урок
                        // это проговаривает); сцена сжимает обе попытки в одно нажатие.
                        message = "Шаг ${index + 1}: «готово» без доказательства — не засчитано, шаг заблокирован"
                    }
                },
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp),
        ) {
            Text(
                text = if (rejected) "Движок плана: сделал — докажи" else "Движок плана",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (rejected) error else SceneInk.copy(alpha = 0.6f),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, color = if (rejected) error else SceneInk, minLines = 2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Завершено: $provenCount из ${PLAN.size}",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (provenCount == PLAN.size) ProvenGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    attached = 0; verified = 0; blocked = 0; rejected = false
                    message = "Жми «Сдать» у шага 1 — посмотрим, поверит ли движок на слово"
                },
                enabled = attached != 0 || blocked != 0,
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Сначала")
            }
        }
    }
}

@Composable
private fun StepCard(number: Int, step: PlanStep, status: StepStatus, attached: Boolean, onAttach: () -> Unit, onDone: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error
    val active = status == StepStatus.Ready || status == StepStatus.Blocked
    val accent = when (status) {
        StepStatus.Waiting -> SceneInk.copy(alpha = 0.25f)
        StepStatus.Ready -> primary
        StepStatus.Blocked -> error
        StepStatus.Verified -> ProvenGreen
    }
    val borderColor by animateColorAsState(accent, tween(250), label = "stepBorder")
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (status == StepStatus.Waiting) 0.6f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp).clip(CircleShape).background(ScenePalette[0])) {
                Text(if (status == StepStatus.Verified) "✓" else "$number", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = SceneInk)
            }
            Text(step.title, style = MaterialTheme.typography.bodyMedium, color = SceneInk, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            // Фиксированная ширина: смена статуса не должна переносить заголовок шага.
            // 112dp — под самую длинную подпись PlanCard («Готов к запуску») в одну строку.
            Box(contentAlignment = Alignment.Center, modifier = Modifier.width(112.dp).clip(RoundedCornerShape(50)).background(accent).padding(vertical = 4.dp)) {
                Text(
                    text = status.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (status == StepStatus.Waiting) SceneInk else Color.White,
                    maxLines = 1,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (attached) ScenePalette[2] else Color.White)
                    .border(1.dp, if (attached) Color.Transparent else primary.copy(alpha = if (active) 0.6f else 0.2f), RoundedCornerShape(10.dp))
                    .clickable(enabled = active && !attached, role = Role.Button, onClick = onAttach)
                    .padding(horizontal = 8.dp),
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = SceneInk.copy(alpha = if (attached || active) 0.8f else 0.35f), modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (attached) step.evidence else "приложить доказательство",
                    style = MaterialTheme.typography.labelMedium,
                    color = SceneInk.copy(alpha = if (attached || active) 1f else 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onDone, enabled = active) { Text("Сдать") }
        }
    }
}
