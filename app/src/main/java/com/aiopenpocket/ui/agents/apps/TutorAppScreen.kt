package com.aiopenpocket.ui.agents.apps

// @trace agent-apps-native-screens-for-the-preset-agents-c8d8ce

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.srs.SrsRepository
import com.aiopenpocket.agent.core.srs.SrsScheduler
import com.aiopenpocket.agent.core.srs.SrsWord
import com.aiopenpocket.agent.tools.startOfTodayMillis
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.GlassCard
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Identity of a card inside one session; matches the repository's "<lang>:<term>" key. */
private fun SrsWord.key(): String = "${lang.trim().lowercase()}:${term.trim().lowercase()}"

data class TutorAppState(
  val words: List<SrsWord> = emptyList(),
  /** Null = all languages. */
  val lang: String? = null,
  val showDictionary: Boolean = false,
  val flipped: Boolean = false,
  /** Cards graded in this visit; they leave today's queue even if a refresh brings them back. */
  val gradedKeys: Set<String> = emptySet(),
  val todayStartMs: Long = 0L,
  val loaded: Boolean = false,
) {
  val languages: List<String> get() = words.map { it.lang }.distinct().sorted()
  private val visible: List<SrsWord> get() = words.filter { lang == null || it.lang == lang }
  val dictionary: List<SrsWord> get() = visible
  val due: List<SrsWord>
    get() = visible
      .filter { SrsScheduler.isDue(it.dueMs, todayStartMs) && it.key() !in gradedKeys }
      .sortedBy { it.dueMs }
  val current: SrsWord? get() = due.firstOrNull()
}

/**
 * Reads and grades the Tutor's words through the same repository and scheduler as the agent's tool
 * (SrsTool), so a grade given on this screen moves the very schedule the agent talks about in chat.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class TutorAppViewModel(
  private val repository: SrsRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(TutorAppState())
  val state: StateFlow<TutorAppState> = _state.asStateFlow()

  init {
    refresh()
  }

  /** Pick up words the agent added in chat while this screen was in the background. */
  fun refresh() {
    viewModelScope.launch {
      val words = repository.list(null)
      _state.update { s ->
        s.copy(
          words = words,
          todayStartMs = startOfTodayMillis(),
          loaded = true,
          lang = s.lang?.takeIf { l -> words.any { it.lang == l } },
        )
      }
    }
  }

  fun selectLang(lang: String?) = _state.update { it.copy(lang = lang, flipped = false) }

  fun showDictionary(show: Boolean) = _state.update { it.copy(showDictionary = show) }

  fun flip() = _state.update { it.copy(flipped = !it.flipped) }

  fun grade(correct: Boolean) {
    val word = _state.value.current ?: return
    val stage = SrsScheduler.nextStage(word.stage, correct)
    val updated = word.copy(stage = stage, dueMs = SrsScheduler.nextDueMillis(startOfTodayMillis(), stage))
    // Update the screen at once; the write follows. The queue is keyed, so a slow write cannot re-show it.
    _state.update { s ->
      s.copy(
        flipped = false,
        gradedKeys = s.gradedKeys + word.key(),
        words = s.words.map { if (it.key() == word.key()) updated else it },
      )
    }
    viewModelScope.launch { repository.upsert(updated) }
  }
}

/** The Tutor agent's app: a flip-card review of today's due words and the dictionary behind it. */
@Composable
fun TutorApp(modifier: Modifier = Modifier) {
  val viewModel: TutorAppViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

  Column(modifier = modifier.fillMaxSize().padding(horizontal = 20.dp)) {
    if (!state.loaded) return@Column
    if (state.words.isEmpty()) {
      TutorEmpty()
      return@Column
    }
    TutorHeader(state, viewModel)
    Spacer(Modifier.height(12.dp))
    if (state.showDictionary) {
      TutorDictionary(state.dictionary, state.todayStartMs, Modifier.weight(1f))
    } else {
      TutorSession(state, viewModel, Modifier.weight(1f))
    }
  }
}

@Composable
private fun TutorEmpty() {
  GlassCard(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
        tr("Пока нет слов для повторения.", "No words to review yet."),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        tr(
          "Попроси Репетитора в чате добавить слова, например: «добавь 10 слов по теме еда».",
          "Ask the Tutor in chat to add words, for example: \"add 10 words about food\".",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun TutorHeader(state: TutorAppState, viewModel: TutorAppViewModel) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      tr("К повторению сегодня: ${state.due.size} · Всего: ${state.dictionary.size}",
        "Due today: ${state.due.size} · Total: ${state.dictionary.size}"),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.testTag("tutor_counters"),
    )
    if (state.languages.size > 1) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = state.lang == null,
          onClick = { viewModel.selectLang(null) },
          label = { Text(tr("Все", "All")) },
        )
        state.languages.forEach { l ->
          FilterChip(
            selected = state.lang == l,
            onClick = { viewModel.selectLang(l) },
            label = { Text(l.uppercase()) },
          )
        }
      }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = !state.showDictionary,
        onClick = { viewModel.showDictionary(false) },
        label = { Text(tr("Повторение", "Review")) },
      )
      FilterChip(
        selected = state.showDictionary,
        onClick = { viewModel.showDictionary(true) },
        label = { Text(tr("Словарь", "Dictionary")) },
      )
    }
  }
}

@Composable
private fun TutorSession(state: TutorAppState, viewModel: TutorAppViewModel, modifier: Modifier) {
  val card = state.current
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    if (card == null) {
      GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            tr("На сегодня всё!", "All done for today!"),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            tr("Новые слова можно добавить через Репетитора в чате.", "Ask the Tutor in chat for new words."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      return@Column
    }
    FlipCard(card = card, flipped = state.flipped, onFlip = viewModel::flip)
    Spacer(Modifier.height(20.dp))
    if (state.flipped) {
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
          onClick = { viewModel.grade(false) },
          modifier = Modifier.weight(1f).testTag("tutor_again"),
        ) { Text(tr("Ещё раз", "Again")) }
        Button(
          onClick = { viewModel.grade(true) },
          modifier = Modifier.weight(1f).testTag("tutor_good"),
        ) { Text(tr("Знаю", "Good")) }
      }
    } else {
      Text(
        tr("Нажми на карточку, чтобы увидеть перевод", "Tap the card to see the translation"),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** 3D flip: the front is the word, the back the translation and the example. */
@Composable
private fun FlipCard(card: SrsWord, flipped: Boolean, onFlip: () -> Unit) {
  val rotation by animateFloatAsState(
    targetValue = if (flipped) 180f else 0f,
    animationSpec = if (flipped) tween(450, easing = FastOutSlowInEasing) else snap(),
    label = "tutor_card_rotation",
  )
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .height(280.dp)
      .graphicsLayer {
        rotationY = rotation
        cameraDistance = 12f * density
      }
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onFlip)
      .testTag("tutor_card"),
    contentAlignment = Alignment.Center,
  ) {
    if (rotation < 90f) {
      GlassCard(modifier = Modifier.fillMaxSize()) {
        Column(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            card.term,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(Modifier.height(8.dp))
          Text(
            card.lang.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      // Mirror the back so it reads correctly after the half-turn.
      Box(modifier = Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
        GlassCard(modifier = Modifier.fillMaxSize()) {
          Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
          ) {
            Text(
              card.translation,
              fontSize = 26.sp,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center,
              color = MaterialTheme.colorScheme.primary,
            )
            if (card.example.isNotBlank()) {
              Spacer(Modifier.height(14.dp))
              Text(
                "\"${card.example}\"",
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TutorDictionary(words: List<SrsWord>, todayStartMs: Long, modifier: Modifier) {
  val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
  LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    items(words, key = { it.key() }) { w ->
      GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
              w.term,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.weight(1f),
            )
            Text(
              w.lang.uppercase(),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(w.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
          val next = if (SrsScheduler.isDue(w.dueMs, todayStartMs)) {
            tr("Повторить сегодня", "Due today")
          } else {
            tr("Следующий повтор: ", "Next review: ") + dateFormat.format(Date(w.dueMs))
          }
          Text(next, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
  }
}
