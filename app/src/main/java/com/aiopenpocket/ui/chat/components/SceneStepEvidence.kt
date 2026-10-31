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
import com.aiopenpocket.i18n.tr

/** A plan step and the saved result the engine will accept as evidence for its criterion. */
private class PlanStep(val title: String, val evidence: String)

// The plan from the lesson's practice: find three articles, write out the main ideas, gather them into one list.
// Functions, not top-level vals: tr() has to run when the scene is shown, in the current language.
private fun planSteps() = listOf(
    PlanStep(tr("Найти три статьи по теме", "Find three articles on the topic"), tr("3 ссылки сохранены", "3 links saved")),
    PlanStep(tr("Выписать из каждой главную мысль", "Write out the main idea of each"), tr("3 выписки в заметке", "3 excerpts in a note")),
    PlanStep(tr("Собрать мысли в один список", "Gather the ideas into one list"), tr("список из 3 пунктов", "a list of 3 items")),
)

private fun evidenceHint() = tr("Жми «Сдать» у шага 1 — посмотрим, поверит ли движок на слово", "Tap “Submit” on step 1 and see whether the engine takes your word for it")

/**
 * Step statuses — in the same words as on the real plan card in the chat (PlanCard): those are exactly what the
 * learner will see later. "Waiting" — until the previous step is done.
 */
private enum class StepStatus {
    Waiting, Ready, Blocked, Verified;

    val label: String
        get() = when (this) {
            Waiting -> tr("Ожидает", "Waiting")
            Ready -> tr("Готов к запуску", "Ready to run")
            Blocked -> tr("Заблокирован", "Blocked")
            Verified -> tr("Завершён", "Completed")
        }
}

private val ProvenGreen = Color(0xFF2E7D32)

private fun Int.hasStep(index: Int) = (this and (1 shl index)) != 0

/**
 * "Done — prove it": the user plays the agent and taps "Submit" on the plan's steps. The engine does not count
 * a bare "done" — the step is blocked until evidence is attached. The next step opens only after a checked
 * one, and the plan is done only when every step has its evidence.
 */
@Composable
internal fun StepEvidenceScene() {
    val plan = planSteps()
    // Bitmasks per step: evidence attached / checked / blocked.
    var attached by rememberSaveable { mutableIntStateOf(0) }
    var verified by rememberSaveable { mutableIntStateOf(0) }
    var blocked by rememberSaveable { mutableIntStateOf(0) }
    var message by rememberSaveable { mutableStateOf(evidenceHint()) }
    var rejected by rememberSaveable { mutableStateOf(false) }
    val provenCount = verified.countOneBits()
    val error = MaterialTheme.colorScheme.error

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Ты — агент. План одобрен, выполняем по шагам", "You are the agent. The plan is approved, so we run it step by step"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        plan.forEachIndexed { index, step ->
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
                    message = tr("Доказательство «${step.evidence}» приложено — теперь жми «Сдать»", "Proof “${step.evidence}” attached. Now tap “Submit”")
                    rejected = false
                },
                onDone = {
                    if (attached.hasStep(index)) {
                        verified = verified or bit
                        blocked = blocked and bit.inv()
                        rejected = false
                        message = if (index == plan.lastIndex) {
                            tr("План выполнен — каждый шаг с доказательством. На слово не верим", "The plan is done: every step has proof. We don't take anyone's word for it")
                        } else {
                            tr("Шаг ${index + 1} завершён: критерий подтверждён. Открылся шаг ${index + 2}", "Step ${index + 1} completed: the criterion is confirmed. Step ${index + 2} is now open")
                        }
                    } else {
                        blocked = blocked or bit
                        rejected = true
                        // The real engine first gives the step another attempt and blocks after the second (the lesson
                        // says so); the scene squeezes both attempts into one tap.
                        message = tr("Шаг ${index + 1}: «готово» без доказательства — не засчитано, шаг заблокирован", "Step ${index + 1}: “done” without proof is not counted, and the step is blocked")
                    }
                },
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp),
        ) {
            Text(
                text = if (rejected) tr("Движок плана: сделал — докажи", "Plan engine: done means proven") else tr("Движок плана", "Plan engine"),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (rejected) error else SceneInk.copy(alpha = 0.6f),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium, color = if (rejected) error else SceneInk, minLines = 2)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = tr("Завершено: $provenCount из ${plan.size}", "Completed: $provenCount of ${plan.size}"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (provenCount == plan.size) ProvenGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    attached = 0; verified = 0; blocked = 0; rejected = false
                    message = evidenceHint()
                },
                enabled = attached != 0 || blocked != 0,
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("Сначала", "Start over"))
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
            // A fixed width: a status change must not wrap the step's heading.
            // 112dp — for PlanCard's longest label ("Ready to run") on one line.
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
                    text = if (attached) step.evidence else tr("приложить доказательство", "attach proof"),
                    style = MaterialTheme.typography.labelMedium,
                    color = SceneInk.copy(alpha = if (attached || active) 1f else 0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onDone, enabled = active) { Text(tr("Сдать", "Submit")) }
        }
    }
}
