package dev.amuse.forge.gateway.market.ai

import dev.amuse.forge.gateway.RateLimiter
import dev.amuse.forge.gateway.TaskRunner
import dev.amuse.forge.gateway.ai.MetaModels
import dev.amuse.forge.gateway.market.MarketStore
import org.slf4j.LoggerFactory

// @trace market-ai-8f3b21

/** Brakes on the paid side of the market: every smart search that misses the cache is a model call. */
data class MarketAiLimits(
    val matchesPerHour: Int = 20,
    /** Across all clients, so a crowd (or a botnet) can't run up the bill either. */
    val matchesPerHourGlobal: Int = 300,
    val maxQueryChars: Int = 300,
    /** Detail translations that miss the cache, per client. */
    val translationsPerHour: Int = 30,
    /** Every translation that misses the cache, detail or background, across all clients. */
    val translationsPerHourGlobal: Int = 500,
)

/**
 * After a publish: review the new version and, for a new listing, draw its cover. Runs on a
 * [TaskRunner] so the person never waits for a model. Each step checks the store first, so running it
 * twice (a resume after a restart racing a fresh publish) costs at most one wasted call.
 */
class MarketEnricher(
    private val store: MarketStore,
    private val reviewer: ListingReviewer,
    private val artist: CoverArtist,
    private val runner: TaskRunner,
) {
    private val log = LoggerFactory.getLogger(MarketEnricher::class.java)

    fun published(listingId: String) {
        runner.submit("enrich $listingId") { enrich(listingId) }
    }

    /** Everything a crash or a model outage left without a review or a cover. */
    suspend fun resumePending() {
        store.pendingEnrichment().forEach { published(it) }
    }

    private suspend fun enrich(listingId: String) {
        val input = store.reviewInput(listingId) ?: return
        if (input.needsReview) {
            runCatching { reviewer.review(input.title, input.summary, input.version) }
                .onFailure { log.warn("Review of {} failed: {}", listingId, it.message) }
                .getOrNull()
                ?.let { store.applyReview(listingId, it) }
                ?: log.warn("Review of {} v{} gave no usable answer", listingId, input.version.version)
        }
        if (input.needsCover) {
            runCatching { artist.draw(input.title, input.summary) }
                .onFailure { log.warn("Cover for {} failed: {}", listingId, it.message) }
                .getOrNull()
                ?.let { store.attachCover(listingId, it.bytes, it.mediaType) }
        }
    }
}

/** The market's AI features as one unit; null in [dev.amuse.forge.gateway.forgeModule] when there is no Meta key. */
class MarketAi(
    val enricher: MarketEnricher,
    val matcher: MarketMatcher,
    val translations: MarketTranslations,
    val limits: MarketAiLimits,
) {
    val matchLimiter = RateLimiter(limits.matchesPerHour, HOUR_MILLIS)
    val globalMatchLimiter = RateLimiter(limits.matchesPerHourGlobal, HOUR_MILLIS)

    companion object {
        private const val HOUR_MILLIS = 60 * 60 * 1000L

        fun create(store: MarketStore, models: MetaModels, runner: TaskRunner, limits: MarketAiLimits = MarketAiLimits()) =
            MarketAi(
                enricher = MarketEnricher(store, ListingReviewer(models), CoverArtist(models), runner),
                matcher = MarketMatcher(store, models),
                translations = MarketTranslations(store, ListingTranslator(models), runner, limits),
                limits = limits,
            )
    }
}
