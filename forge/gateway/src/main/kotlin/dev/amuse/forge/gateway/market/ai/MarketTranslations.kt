package dev.amuse.forge.gateway.market.ai

import dev.amuse.forge.contract.CardText
import dev.amuse.forge.contract.ListingDetail
import dev.amuse.forge.contract.ListingSummary
import dev.amuse.forge.contract.ListingTranslation
import dev.amuse.forge.gateway.JobStore
import dev.amuse.forge.gateway.RateLimiter
import dev.amuse.forge.gateway.TaskRunner
import dev.amuse.forge.gateway.market.MarketStore
import dev.amuse.forge.gateway.market.StoredTranslation
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

// @trace market-translation-6c2e94

/**
 * Listings in the reader's language. A detail asked for in another language is translated on the
 * spot (one call, then cached on disk per text and language); search never waits: it hands back the
 * translations it has and queues the rest. Every translation that is not a cache hit is a paid call,
 * so the detail path is limited per client and everything is limited in total; over a limit the
 * reader simply gets the original.
 */
class MarketTranslations(
    private val store: MarketStore,
    private val translator: ListingTranslator,
    private val runner: TaskRunner,
    limits: MarketAiLimits,
) {
    private val log = LoggerFactory.getLogger(MarketTranslations::class.java)
    private val perClient = RateLimiter(limits.translationsPerHour, HOUR_MILLIS)
    private val global = RateLimiter(limits.translationsPerHourGlobal, HOUR_MILLIS)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** [detail] in [lang], or null: same language, over a limit, or the model failed. */
    suspend fun forDetail(detail: ListingDetail, lang: String, client: String): ListingTranslation? {
        val hash = sourceHash(detail)
        cached(detail.listing.listingId, lang, hash)?.let { return it.translation }
        if (!perClient.tryAcquire(client) || !global.tryAcquire(GLOBAL)) return null
        return translateAndStore(detail, lang, hash)?.translation
    }

    /** [items] with the card translations already cached; the missing ones are queued. */
    suspend fun forCards(items: List<ListingSummary>, lang: String): List<ListingSummary> = items.map { item ->
        val stored = store.translation(item.listingId, lang)
        val detail = stored?.let { store.get(item.listingId) }
        if (stored != null && detail != null && stored.sourceHash == sourceHash(detail)) {
            item.copy(translation = stored.translation?.let { CardText(lang, it.title, it.summary) })
        } else {
            queue(item.listingId, lang)
            item
        }
    }

    private fun queue(listingId: String, lang: String) {
        val key = "$listingId:$lang"
        if (!inFlight.add(key)) return
        runner.submit("translate $key") {
            try {
                val detail = store.get(listingId) ?: return@submit
                val hash = sourceHash(detail)
                if (cached(listingId, lang, hash) == null && global.tryAcquire(GLOBAL)) translateAndStore(detail, lang, hash)
            } finally {
                inFlight.remove(key)
            }
        }
    }

    private suspend fun cached(listingId: String, lang: String, hash: String): StoredTranslation? =
        store.translation(listingId, lang)?.takeIf { it.sourceHash == hash }

    private suspend fun translateAndStore(detail: ListingDetail, lang: String, hash: String): StoredTranslation? {
        val outcome = runCatching { translator.translate(detail, lang) }
            .onFailure { log.warn("Translating {} into {} failed: {}", detail.listing.listingId, lang, it.message) }
            .getOrNull()
        val stored = when (outcome) {
            is TranslationOutcome.Translated -> StoredTranslation(hash, outcome.translation.sourceLang, outcome.translation)
            is TranslationOutcome.SameLanguage -> StoredTranslation(hash, outcome.sourceLang, null)
            TranslationOutcome.Failed, null -> return null
        }
        store.saveTranslation(detail.listing.listingId, lang, stored)
        return stored
    }

    private fun sourceHash(detail: ListingDetail): String {
        val r = detail.review
        val text = listOf(detail.listing.title, detail.listing.summary, detail.version.sha256, "${r?.version}:${r?.reviewedAt}")
        return JobStore.sha256Hex(text.joinToString("\u0000").toByteArray()).take(HASH_CHARS)
    }

    companion object {
        val LANG = Regex("[a-z]{2}")
        private const val HOUR_MILLIS = 60 * 60 * 1000L
        private const val GLOBAL = "global"
        private const val HASH_CHARS = 24
    }
}
