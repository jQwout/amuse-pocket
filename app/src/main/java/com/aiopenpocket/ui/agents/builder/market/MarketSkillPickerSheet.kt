package com.aiopenpocket.ui.agents.builder.market

// @trace skill-from-the-market-in-the-agent-builder-acd66a

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiopenpocket.agent.core.market.MarketListingReview
import com.aiopenpocket.agent.core.market.MarketReviewVerdict
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.CustomSkillDraft
import com.aiopenpocket.ui.agents.builder.components.BuilderTextField
import com.aiopenpocket.ui.components.glass.glassCardSurface
import dev.zacsweers.metrox.viewmodel.metroViewModel

/**
 * "From the market" on the CAPABILITIES step: a sheet over the wizard with the shared skills, one
 * card at a time, and "Add to agent" at the bottom of the whole instruction. The whole instruction,
 * on purpose: a stranger's text is about to go into this agent's prompt, so the person reads what the
 * agent will read, with Muse's plain-words review above it, and the cover and safety badge are a
 * summary, never a substitute. Hosted by `AgentBuilderRoute` (it needs the ViewModel); the screen stays
 * a pure projector.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketSkillPickerSheet(
  agentId: String,
  takenIds: Set<String>,
  onAdd: (CustomSkillDraft) -> Unit,
  onDismiss: () -> Unit,
) {
  val viewModel: MarketSkillPickerViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  LaunchedEffect(Unit) { viewModel.start() }

  fun dismiss() {
    viewModel.reset()
    onDismiss()
  }

  // Fully open at once: half-open (the default first stop) left "Add to agent" below the fold on the
  // emulator, and a person who had read the instruction had to drag the sheet up to find the button.
  ModalBottomSheet(
    onDismissRequest = ::dismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    MarketSkillPickerContent(
      state = state,
      onQueryChange = viewModel::setQuery,
      onRetry = viewModel::refresh,
      onOpen = viewModel::open,
      onBack = viewModel::closeDetail,
      onAdd = {
        viewModel.importOpened(agentId, takenIds)?.let { skill ->
          onAdd(skill)
          dismiss()
        }
      },
    )
  }
}

/** The sheet's content without the sheet, so a screenshot test can capture it (a `ModalBottomSheet` is a popup window). */
@Composable
internal fun MarketSkillPickerContent(
  state: MarketSkillPickerState,
  onQueryChange: (String) -> Unit,
  onRetry: () -> Unit,
  onOpen: (String) -> Unit,
  onBack: () -> Unit,
  onAdd: () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .fillMaxHeight(SHEET_HEIGHT_FRACTION)
      .padding(horizontal = 20.dp)
      .testTag("market_skill_picker"),
  ) {
    val opened = state.opened
    if (opened == null) {
      Text(text = tr("Навыки из маркета", "Skills from the market"), style = MaterialTheme.typography.headlineSmall)
      Text(
        text = tr(
          "Навыки, которыми поделились другие. Добавленный навык становится своим навыком агента.",
          "Skills other people shared. An added skill becomes this agent's own skill.",
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
      )
      BuilderTextField(
        value = state.query,
        onValueChange = onQueryChange,
        placeholder = tr("Поиск по названию и тегам", "Search by name and tags"),
        modifier = Modifier.fillMaxWidth().testTag("market_skill_search"),
      )
      Spacer(modifier = Modifier.height(12.dp))
      SkillList(state = state, onRetry = onRetry, onOpen = onOpen)
    } else {
      SkillDetail(opened = opened, onBack = onBack, onAdd = onAdd)
    }
  }
}

@Composable
private fun SkillList(state: MarketSkillPickerState, onRetry: () -> Unit, onOpen: (String) -> Unit) {
  when {
    state.isLoading && state.cards.isEmpty() -> Centered { CircularProgressIndicator() }
    state.error != null && state.cards.isEmpty() -> Centered {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry) { Text(tr("Повторить", "Retry")) }
      }
    }
    state.cards.isEmpty() -> Centered {
      Text(
        text = if (state.query.isBlank()) {
          tr("В маркете пока нет навыков.", "No skills on the market yet.")
        } else {
          tr("Ничего не нашлось.", "Nothing found.")
        },
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      items(state.cards, key = { it.listing.listingId }) { card ->
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .glassCardSurface()
            .clickable { onOpen(card.listing.listingId) }
            .testTag("market_skill_${card.listing.listingId}")
            .padding(12.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Cover(url = card.coverUrl, size = 56.dp)
          Spacer(modifier = Modifier.width(12.dp))
          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(text = card.listing.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
              SafetyBadge(card.listing.safety)
            }
            Text(
              text = card.listing.summary,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
              text = byLine(card.listing.publisherName, card.listing.installs),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
      item { Spacer(modifier = Modifier.height(24.dp)) }
    }
  }
}

@Composable
private fun SkillDetail(opened: OpenedMarketSkill, onBack: () -> Unit, onAdd: () -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    TextButton(onClick = onBack, modifier = Modifier.testTag("market_skill_back")) { Text(tr("← К списку", "← Back to the list")) }
    when {
      opened.isLoading -> Centered { CircularProgressIndicator() }
      opened.error != null -> Centered { Text(text = opened.error, color = MaterialTheme.colorScheme.error) }
      opened.detail != null -> {
        val detail = opened.detail
        val skill = detail.version.skill
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Cover(url = opened.coverUrl, size = 72.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(text = detail.listing.title, style = MaterialTheme.typography.titleLarge)
              Text(
                text = byLine(detail.listing.publisherName, detail.listing.installs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              SafetyBadge(detail.review?.verdict ?: detail.listing.safety)
            }
          }
          Spacer(modifier = Modifier.height(8.dp))
          Text(text = detail.listing.summary, style = MaterialTheme.typography.bodyMedium)

          detail.review?.let { review ->
            Spacer(modifier = Modifier.height(12.dp))
            ReviewBlock(review)
          }

          if (skill != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Labeled(tr("Когда использовать", "When to use"), skill.whenToUse)
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = tr("Инструкция целиком", "The whole instruction"), style = MaterialTheme.typography.titleSmall)
            Text(
              text = tr(
                "Это и прочитает агент, когда загрузит навык. Прочитай сам, прежде чем добавлять.",
                "This is what the agent will read when it loads the skill. Read it yourself before adding.",
              ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = skill.instructionsMarkdown,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.fillMaxWidth().glassCardSurface().padding(12.dp).testTag("market_skill_instructions"),
            )
          }
          Spacer(modifier = Modifier.height(12.dp))
        }
        opened.addError?.let { error ->
          Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          Spacer(modifier = Modifier.height(6.dp))
        }
        Button(
          onClick = onAdd,
          enabled = skill != null,
          modifier = Modifier.fillMaxWidth().testTag("market_skill_add"),
        ) { Text(tr("Добавить агенту", "Add to agent")) }
        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }
}

/** Muse's plain-words review: what the skill really does, then what to look at, with the quotes that raised it. */
@Composable
private fun ReviewBlock(review: MarketListingReview) {
  Column(modifier = Modifier.fillMaxWidth().glassCardSurface().padding(12.dp)) {
    Text(text = tr("Отзыв Muse", "Muse's review"), style = MaterialTheme.typography.titleSmall)
    Text(text = review.summary, style = MaterialTheme.typography.bodySmall)
    if (review.capabilities.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      review.capabilities.forEach { Text(text = "• $it", style = MaterialTheme.typography.bodySmall) }
    }
    if (review.concerns.isNotEmpty()) {
      Spacer(modifier = Modifier.height(6.dp))
      Text(text = tr("На что посмотреть", "What to look at"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
      review.concerns.forEach { concern ->
        Text(text = concern.explanation, style = MaterialTheme.typography.bodySmall)
        if (concern.quote.isNotBlank()) {
          Text(
            text = "“${concern.quote}”",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun SafetyBadge(verdict: MarketReviewVerdict?) {
  val (label, color) = when (verdict) {
    MarketReviewVerdict.SAFE -> tr("проверен", "reviewed: safe") to MaterialTheme.colorScheme.primary
    MarketReviewVerdict.CAUTION -> tr("прочитай сначала", "read first") to MaterialTheme.colorScheme.error
    MarketReviewVerdict.BLOCK -> tr("заблокирован", "blocked") to MaterialTheme.colorScheme.error
    // Null is "not reviewed yet", said as such: an unreviewed skill must not look like a safe one.
    null -> tr("ещё не проверен", "not reviewed yet") to MaterialTheme.colorScheme.onSurfaceVariant
  }
  Text(text = label, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun Cover(url: String?, size: Dp) {
  val shape = RoundedCornerShape(12.dp)
  if (url == null) {
    Box(modifier = Modifier.size(size).clip(shape).glassCardSurface())
  } else {
    AsyncImage(
      model = url,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(size).clip(shape),
    )
  }
}

@Composable
private fun Labeled(label: String, value: String) {
  Text(text = label, style = MaterialTheme.typography.titleSmall)
  Text(text = value, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
  Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { content() }
}

private fun byLine(publisher: String, installs: Int): String =
  tr("от $publisher · установок: $installs", "by $publisher · $installs installs")

private const val SHEET_HEIGHT_FRACTION = 0.9f
