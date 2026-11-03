package com.aiopenpocket.ui.agents.builder.market

// @trace skill-from-the-market-in-the-agent-builder-acd66a

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.forge.ForgeResult
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.agent.core.market.MarketClient
import com.aiopenpocket.agent.core.market.MarketListingDetail
import com.aiopenpocket.agent.core.market.MarketListingSummary
import com.aiopenpocket.agent.core.skill.validateUserSkill
import com.aiopenpocket.di.AppScope
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.agents.builder.CustomSkillDraft
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One search card with its cover resolved to an absolute URL the image loader can fetch. */
data class MarketSkillCard(val listing: MarketListingSummary, val coverUrl: String?)

/** The listing the person tapped, while it loads and once it is here. */
data class OpenedMarketSkill(
  val listingId: String,
  val isLoading: Boolean = true,
  val error: String? = null,
  val detail: MarketListingDetail? = null,
  val coverUrl: String? = null,
  /** Why "Add to agent" refused the package (the app's own skill rules), or null. */
  val addError: String? = null,
)

data class MarketSkillPickerState(
  val query: String = "",
  val isLoading: Boolean = false,
  val error: String? = null,
  val cards: List<MarketSkillCard> = emptyList(),
  val total: Int = 0,
  val opened: OpenedMarketSkill? = null,
)

/**
 * Drives the "From the market" sheet of the CAPABILITIES step: search, open one listing, hand its
 * package over as a [CustomSkillDraft]. The import is checked by the app's own [validateUserSkill]
 * before it reaches the draft, the same rules a hand-typed skill meets at publish, so a package the
 * gateway accepted but this build would refuse is turned down here with the reason instead of
 * blocking Publish three steps later. The install counter is bumped after a successful add and
 * never waits for the person.
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class MarketSkillPickerViewModel(
  private val client: MarketClient,
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  private val _state = MutableStateFlow(MarketSkillPickerState())
  val state: StateFlow<MarketSkillPickerState> = _state.asStateFlow()

  private var searchJob: Job? = null

  /** First open of the sheet: load the newest skills once; a reopen keeps what is there. */
  fun start() {
    if (_state.value.cards.isEmpty() && !_state.value.isLoading) search(debounceMs = 0)
  }

  fun setQuery(query: String) {
    _state.update { it.copy(query = query) }
    search(debounceMs = SEARCH_DEBOUNCE_MS)
  }

  fun refresh() = search(debounceMs = 0)

  private fun search(debounceMs: Long) {
    searchJob?.cancel()
    searchJob = viewModelScope.launch {
      if (debounceMs > 0) delay(debounceMs)
      _state.update { it.copy(isLoading = true, error = null) }
      when (val result = client.searchSkills(_state.value.query)) {
        is ForgeResult.Ok -> {
          val cards = result.value.items.map { MarketSkillCard(it, it.coverUrl?.let { url -> client.absoluteUrl(url) }) }
          _state.update { it.copy(isLoading = false, cards = cards, total = result.value.total) }
        }
        is ForgeResult.Failed -> _state.update { it.copy(isLoading = false, error = result.message) }
      }
    }
  }

  fun open(listingId: String) {
    _state.update { it.copy(opened = OpenedMarketSkill(listingId)) }
    viewModelScope.launch {
      val opened = when (val result = client.listing(listingId)) {
        is ForgeResult.Ok -> OpenedMarketSkill(
          listingId = listingId,
          isLoading = false,
          detail = result.value,
          coverUrl = result.value.listing.coverUrl?.let { client.absoluteUrl(it) },
        )
        is ForgeResult.Failed -> OpenedMarketSkill(listingId, isLoading = false, error = result.message)
      }
      // The person may have gone back to the list meanwhile: do not reopen the card on them.
      _state.update { s -> if (s.opened?.listingId == listingId) s.copy(opened = opened) else s }
    }
  }

  fun closeDetail() {
    _state.update { it.copy(opened = null) }
  }

  /** Back to the list, with the search kept: the sheet is being dismissed. */
  fun reset() {
    _state.update { it.copy(opened = null) }
  }

  /**
   * The opened package as this agent's own skill, or null (with [OpenedMarketSkill.addError] set) when
   * the app's skill rules refuse it. [takenIds] are the ids the draft already uses, so a collision gets
   * a suffix instead of two skills answering to one id.
   */
  fun importOpened(agentId: String, takenIds: Set<String>): CustomSkillDraft? {
    val opened = _state.value.opened ?: return null
    val pkg = opened.detail?.version?.skill ?: run {
      _state.update { it.copy(opened = opened.copy(addError = tr("В этом листинге нет навыка.", "This listing carries no skill."))) }
      return null
    }
    val draft = MarketSkillImport.toDraft(pkg, takenIds)
    val issues = validateUserSkill(draft.toEntity(agentId))
    if (issues.isNotEmpty()) {
      _state.update { it.copy(opened = opened.copy(addError = issues.joinToString("; ") { issue -> issue.message })) }
      return null
    }
    viewModelScope.launch {
      // The counter is the market's, not the person's: a failure here changes nothing on this phone.
      client.install(opened.listingId, userPreferencesRepository.getOrCreateMarketInstallId())
    }
    return draft
  }

  private companion object {
    const val SEARCH_DEBOUNCE_MS = 350L
  }
}
