package com.aiopenpocket.ui.agents.builder

// @trace builder-passport-7d7794
// @trace user-agent-builder-848153

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.agent.AgentDefinition
import com.aiopenpocket.agent.core.agent.AgentPassport
import com.aiopenpocket.agent.core.agent.AgentStatus
import com.aiopenpocket.agent.core.agent.AgentValidator
import com.aiopenpocket.agent.core.agent.UserAgentRepository
import com.aiopenpocket.agent.core.mcp.CapabilityNames
import com.aiopenpocket.agent.tools.AgentDraftTool
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.components.ToolCatalog
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The builder chat's own state: the draft this conversation is building (for the passport above the chat
 * and the last card) and publishing it from that card. Kept out of ChatViewModel, which every chat uses:
 * only the builder chat binds it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class BuilderChatViewModel(
  private val userAgents: UserAgentRepository,
) : ViewModel() {

  data class State(
    val draft: AgentDefinition? = null,
    val publishing: Boolean = false,
    val publishError: String? = null,
  )

  private val conversationId = MutableStateFlow<String?>(null)
  private val publish = MutableStateFlow(State())

  val state: StateFlow<State> = conversationId
    .flatMapLatest { id -> id?.let(AgentDraftTool::draftIdFor)?.let(userAgents::observe) ?: flowOf(null) }
    .combine(publish) { draft, p -> p.copy(draft = draft) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

  fun bind(conversationId: String) {
    this.conversationId.value = conversationId.ifBlank { null }
  }

  /**
   * Publishes this chat's draft: the person's tap on the last card, after the confirmation that lists what
   * the agent may do. Same checks as the review screen's Publish: [AgentValidator] here, and the
   * repository's own `requirePublishable` inside [UserAgentRepository.publish].
   */
  fun publish() {
    val draft = state.value.draft ?: return
    if (draft.status != AgentStatus.DRAFT || publish.value.publishing) return
    viewModelScope.launch {
      if (AgentValidator.validate(draft).isNotEmpty()) {
        publish.value = State(publishError = ERROR_NOT_READY)
        return@launch
      }
      publish.value = State(publishing = true)
      publish.value = try {
        userAgents.publish(draft.id, skills = userAgents.skillsOf(draft.id), hooks = draft.hooks)
        State()
      } catch (e: Exception) {
        // The repository's messages are English `require` texts with ids: not for the person.
        State(publishError = ERROR_PUBLISH)
      }
    }
  }

  private companion object {
    val ERROR_NOT_READY get() = tr("Черновик ещё не готов: открой проверку и поправь.", "The draft isn't ready yet: open the review and fix it.")
    val ERROR_PUBLISH get() = tr("Не удалось опубликовать. Попробуй ещё раз.", "Couldn't publish. Try again.")
  }
}

/**
 * What a builder chat hands to the cards it draws: its live draft and the actions on it. Null in every
 * other chat, in previews and in screenshot tests, where the review card stays the plain "open the review"
 * card it was.
 */
class BuilderChatHost(
  val state: BuilderChatViewModel.State,
  val onPublish: () -> Unit,
  val onOpenAgentChat: (String) -> Unit,
)

val LocalBuilderChatHost = staticCompositionLocalOf<BuilderChatHost?> { null }

/**
 * The passport pinned under the builder chat's header: one line folded, every row unfolded. Once the agent
 * is published the chat can no longer change it (agent_draft refuses), so the bar says so and offers the
 * two ways on: the agent's own chat, or the review screen to change it. Without them an old builder chat
 * with an unanswered card was a dead end (owner, Pixel, 3 Nov 2026).
 */
@Composable
fun BuilderPassportBar(
  draft: AgentDefinition?,
  modifier: Modifier = Modifier,
  onOpenAgentChat: (String) -> Unit = {},
  onEdit: (String) -> Unit = {},
) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val rows = draft?.let(AgentPassport::rows)
  val filled = rows?.count { it.filled } ?: 0
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clickable { expanded = !expanded }
      .padding(horizontal = 16.dp, vertical = 8.dp)
      .animateContentSize(),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = tr("Паспорт агента", "Agent passport"),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = " · " + (draft?.name?.takeIf { it.isNotBlank() } ?: tr("пока без имени", "no name yet")),
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (rows != null) {
        Text(
          text = "$filled/${rows.size}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Icon(
        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
        contentDescription = if (expanded) tr("Свернуть паспорт", "Fold the passport") else tr("Развернуть паспорт", "Unfold the passport"),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    if (draft != null && draft.status == AgentStatus.PUBLISHED) {
      Text(
        text = tr(
          "Агент опубликован: из этого чата его уже не поменять.",
          "The agent is published: this chat can't change it any more.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        Button(onClick = { onOpenAgentChat(draft.id) }, modifier = Modifier.weight(1f)) {
          Text(tr("Открыть чат", "Open its chat"), maxLines = 1)
        }
        OutlinedButton(onClick = { onEdit(draft.id) }, modifier = Modifier.weight(1f)) {
          Text(tr("Изменить", "Change it"), maxLines = 1)
        }
      }
    }
    if (expanded) {
      if (rows == null) {
        Text(
          text = tr(
            "Здесь появится твой агент: что делает, что умеет, что помнит. Он заполняется по мере ответов.",
            "Your agent shows up here: what it does, can use and remembers. It fills in as you answer.",
          ),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(top = 6.dp),
        )
      } else {
        PassportRows(draft, modifier = Modifier.padding(top = 6.dp))
      }
    }
  }
}

/** The passport's rows, then the instructions folded: the same view above the chat and on the last card. */
@Composable
fun PassportRows(draft: AgentDefinition, modifier: Modifier = Modifier) {
  var instructionsOpen by remember(draft.id) { mutableStateOf(false) }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    AgentPassport.rows(draft).forEach { row ->
      Row {
        Text(
          text = row.label,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.width(120.dp),
        )
        Text(
          text = row.value,
          style = MaterialTheme.typography.bodySmall,
          color = if (row.filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (draft.systemPrompt.isNotBlank()) {
      Text(
        text = if (instructionsOpen) tr("Инструкции ▴", "Instructions ▴") else tr("Инструкции ▾", "Instructions ▾"),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable { instructionsOpen = !instructionsOpen }.padding(vertical = 2.dp),
      )
      if (instructionsOpen) {
        Text(text = draft.systemPrompt, style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

/**
 * The confirmation before publishing from the chat. It stands in for the per-tool consent of the form's
 * Capabilities step (RiskConsentDialog): the person ticked these tools on cards, and here they see once
 * more which of them change something before the agent goes live.
 */
@Composable
fun PublishFromChatDialog(draft: AgentDefinition, onConfirm: () -> Unit, onDismiss: () -> Unit) {
  val acting = draft.toolNames.mapNotNull { ToolCatalog.get(it) }.filter { it.requiresConsent }.map { CapabilityNames.tool(it.name) }.sorted()
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(tr("Опубликовать «${draft.name}»?", "Publish “${draft.name}”?")) },
    text = {
      Text(
        buildString {
          append(tr("Агент появится в «Моих агентах», и с ним можно будет говорить.", "The agent appears in “My agents”, and you can talk to it."))
          if (acting.isNotEmpty()) {
            append("\n\n")
            append(tr("Что он может менять: ", "What it can change: "))
            append(acting.joinToString(", "))
            append(".")
          }
          append("\n\n")
          append(tr("Удаление и отправку наружу он всё равно делает только с твоего «да».", "It still deletes or sends things out only after your OK."))
        },
      )
    },
    confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Опубликовать", "Publish")) } },
    dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
  )
}
