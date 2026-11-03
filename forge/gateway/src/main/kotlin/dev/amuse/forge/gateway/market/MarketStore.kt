package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.AgentPackage
import dev.amuse.forge.contract.ListingDetail
import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingPage
import dev.amuse.forge.contract.ListingSort
import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.contract.ListingReview
import dev.amuse.forge.contract.ListingSummary
import dev.amuse.forge.contract.ListingTranslation
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.MarketJson
import dev.amuse.forge.contract.MarketVersionView
import dev.amuse.forge.contract.ModerationFilter
import dev.amuse.forge.contract.ModerationItem
import dev.amuse.forge.contract.ModerationQueue
import dev.amuse.forge.contract.ModerationReport
import dev.amuse.forge.contract.PublishListingRequest
import dev.amuse.forge.contract.PublishListingResponse
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.contract.ReportReason
import dev.amuse.forge.contract.ReviewVerdict
import dev.amuse.forge.contract.SkillPackage
import dev.amuse.forge.contract.UpdateListingRequest
import dev.amuse.forge.gateway.JobStore
import dev.amuse.forge.gateway.secretMatches
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

// @trace skill-market-5d1e7a

/** What an edit-key-protected change came to. */
sealed interface EditResult {
    data class Ok(val detail: ListingDetail) : EditResult
    data object NotFound : EditResult
    data object Forbidden : EditResult
}

@Serializable
data class StoredReport(
    val installId: String,
    val reason: ReportReason,
    val note: String? = null,
    val at: Long,
    /** Salted hash of the reporter's network; raw IPs are never stored. */
    val reporterKey: String = "",
)

/** The full record on disk; [ListingSummary] is what anyone may see of it. */
@Serializable
data class StoredListing(
    val listingId: String,
    val kind: ListingKind,
    val title: String,
    val summary: String,
    val tags: List<String>,
    val publisherName: String,
    val latestVersion: Int,
    val status: ListingStatus,
    val createdAt: Long,
    val updatedAt: Long,
    /** SHA-256 of the edit key; the key itself is only ever in the publish response. */
    val editKeyHash: String,
    val installIds: Set<String> = emptySet(),
    val reports: List<StoredReport> = emptyList(),
    /** The latest AI review; it vouches only for the version it names. */
    val review: ListingReview? = null,
    val cover: StoredCover? = null,
) {
    /** The review, if it is of the version people get now. */
    val currentReview: ListingReview? get() = review?.takeIf { it.version == latestVersion }

    fun toSummary() = ListingSummary(
        listingId = listingId,
        kind = kind,
        title = title,
        summary = summary,
        tags = tags,
        publisherName = publisherName,
        latestVersion = latestVersion,
        installs = installIds.size,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
        coverUrl = cover?.let { MarketRoutes.cover(listingId) },
        safety = currentReview?.verdict,
    )

    fun detail(version: MarketVersionView) = ListingDetail(toSummary(), version, currentReview)
}

@Serializable
data class StoredCover(val mediaType: String, val sizeBytes: Int)

/**
 * One cached translation. [sourceHash] pins it to the exact text it was made from (title, summary,
 * version, review), so any change makes it stale. A null [translation] records "already in that language".
 */
@Serializable
data class StoredTranslation(val sourceHash: String, val sourceLang: String, val translation: ListingTranslation? = null)

/** What the enricher needs to review and draw one listing. */
class ReviewInput(
    val title: String,
    val summary: String,
    val version: MarketVersionView,
    val needsReview: Boolean,
    val needsCover: Boolean,
)

/**
 * Listings in memory behind one mutex, mirrored to `dataDir/market/<id>/` (`listing.json` plus one
 * immutable `v<N>.json` per version) so a restart loses nothing. The same shape as [JobStore], and
 * for the same reason: one Fly machine, a demo-sized catalog. Versions are read from disk on demand.
 *
 * Callers validate requests with [MarketValidator] first; the store normalizes packages so the
 * stored bytes, and so the hash, do not depend on how the client ordered its lists.
 */
class MarketStore(
    dataDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val hideAfterReports: Int = 3,
) {
    private val root = File(dataDir, "market").also { it.mkdirs() }
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val listings = LinkedHashMap<String, StoredListing>()
    private val random = SecureRandom()
    private val salt: String = loadOrCreateSalt()

    /** Bumped on every change; smart search keys its cache on it. */
    @Volatile
    private var revision = 0L

    init {
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            File(dir, LISTING_FILE).takeIf { it.isFile }
                ?.let { runCatching { json.decodeFromString<StoredListing>(it.readText()) }.getOrNull() }
                ?.let { listings[it.listingId] = it }
        }
    }

    suspend fun publish(request: PublishListingRequest): PublishListingResponse = mutex.withLock {
        val t = now()
        val editKey = newEditKey()
        val listing = StoredListing(
            listingId = newId(),
            kind = request.kind,
            title = request.title.trim(),
            summary = request.summary.trim(),
            tags = MarketValidator.normalizeTags(request.tags),
            publisherName = request.publisherName.trim(),
            latestVersion = 1,
            status = ListingStatus.LISTED,
            createdAt = t,
            updatedAt = t,
            editKeyHash = sha256Hex(editKey),
        )
        writeVersion(listing.listingId, buildVersion(1, request.changelog, request.skill, request.agent, t))
        save(listing)
        PublishListingResponse(listing.listingId, 1, editKey)
    }

    /** Listed or unlisted; a hidden or unknown listing is null for everyone. */
    suspend fun get(listingId: String): ListingDetail? = mutex.withLock {
        val listing = visible(listingId) ?: return@withLock null
        readVersion(listingId, listing.latestVersion)?.let { listing.detail(it) }
    }

    suspend fun version(listingId: String, version: Int): MarketVersionView? = mutex.withLock {
        val listing = visible(listingId) ?: return@withLock null
        if (version !in 1..listing.latestVersion) null else readVersion(listingId, version)
    }

    suspend fun search(kind: ListingKind?, query: String?, tag: String?, sort: ListingSort, page: Int, limit: Int): ListingPage =
        mutex.withLock {
            val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val found = listings.values
                .filter { it.status == ListingStatus.LISTED }
                .filter { kind == null || it.kind == kind }
                .filter { tag == null || tag in it.tags }
                .filter { q == null || matches(it, q) }
                .sortedWith(order(sort))
            val items = found.drop((page - 1) * limit).take(limit).map { it.toSummary() }
            ListingPage(items, found.size, page, limit)
        }

    suspend fun addVersion(listingId: String, editKey: String?, request: PublishVersionRequest): EditResult = mutex.withLock {
        val listing = editable(listingId, editKey) ?: return@withLock denied(listingId)
        val t = now()
        val next = listing.latestVersion + 1
        val version = buildVersion(next, request.changelog, request.skill, request.agent, t)
        writeVersion(listingId, version)
        val updated = listing.copy(latestVersion = next, updatedAt = t)
        save(updated)
        EditResult.Ok(updated.detail(version))
    }

    suspend fun update(listingId: String, editKey: String?, request: UpdateListingRequest): EditResult = mutex.withLock {
        val listing = editable(listingId, editKey) ?: return@withLock denied(listingId)
        val updated = listing.copy(
            title = request.title?.trim() ?: listing.title,
            summary = request.summary?.trim() ?: listing.summary,
            tags = request.tags?.let(MarketValidator::normalizeTags) ?: listing.tags,
            status = request.status ?: listing.status,
            updatedAt = now(),
        )
        save(updated)
        val version = readVersion(listingId, updated.latestVersion) ?: return@withLock EditResult.NotFound
        EditResult.Ok(updated.detail(version))
    }

    /** Counts [installId] once. False when the listing is not there to install. */
    suspend fun install(listingId: String, installId: String): Boolean = mutex.withLock {
        val listing = visible(listingId) ?: return@withLock false
        if (installId !in listing.installIds) save(listing.copy(installIds = listing.installIds + installId))
        true
    }

    /**
     * One report per [installId]. The listing hides once [hideAfterReports] distinct networks
     * ([reporter], usually the client IP) reported it: reports are open to anyone, and device ids
     * cost nothing to invent, so they alone must not be able to take a listing down.
     */
    suspend fun report(listingId: String, installId: String, reporter: String, reason: ReportReason, note: String? = null): Boolean =
        mutex.withLock {
            val listing = visible(listingId) ?: return@withLock false
            if (listing.reports.any { it.installId == installId }) return@withLock true
            val reports = listing.reports + StoredReport(installId, reason, note, now(), reporterKey(reporter))
            val status = if (networksOf(reports) >= hideAfterReports) ListingStatus.HIDDEN else listing.status
            save(listing.copy(reports = reports, status = status))
            true
        }

    /** The owner's review list: reports without who sent them, most networks first. */
    suspend fun moderationQueue(filter: ModerationFilter): ModerationQueue = mutex.withLock {
        val items = listings.values
            .filter { if (filter == ModerationFilter.REPORTED) it.reports.isNotEmpty() else it.status == ListingStatus.HIDDEN }
            .sortedWith(compareByDescending<StoredListing> { networksOf(it.reports) }.thenByDescending { it.updatedAt })
            .map { listing ->
                ModerationItem(
                    listing = listing.toSummary(),
                    reportNetworks = networksOf(listing.reports),
                    reports = listing.reports.map { ModerationReport(it.reason, it.note, it.at) },
                    review = listing.review,
                )
            }
        ModerationQueue(items)
    }

    /**
     * Back in search, reports cleared: kept, they would re-hide the listing on the very next report,
     * since the count that hid it is still there.
     */
    suspend fun restore(listingId: String): Boolean = moderate(listingId) {
        it.copy(status = ListingStatus.LISTED, reports = emptyList(), updatedAt = now())
    }

    suspend fun hide(listingId: String): Boolean = moderate(listingId) {
        it.copy(status = ListingStatus.HIDDEN, updatedAt = now())
    }

    /** Gone for good: the listing and every version, from memory and disk. */
    suspend fun remove(listingId: String): Boolean = mutex.withLock {
        listings.remove(listingId) ?: return@withLock false
        dir(listingId).deleteRecursively()
        true
    }

    private suspend fun moderate(listingId: String, change: (StoredListing) -> StoredListing): Boolean = mutex.withLock {
        val listing = listings[listingId] ?: return@withLock false
        save(change(listing))
        true
    }

    fun revision(): Long = revision

    /** The newest version of a visible listing and what it still lacks; null when there is nothing to do it on. */
    suspend fun reviewInput(listingId: String): ReviewInput? = mutex.withLock {
        val listing = visible(listingId) ?: return@withLock null
        val version = readVersion(listingId, listing.latestVersion) ?: return@withLock null
        ReviewInput(listing.title, listing.summary, version, listing.currentReview == null, listing.cover == null)
    }

    /** Visible listings still waiting for a review of their latest version or for a cover. */
    suspend fun pendingEnrichment(): List<String> = mutex.withLock {
        listings.values
            .filter { it.status != ListingStatus.HIDDEN && (it.currentReview == null || it.cover == null) }
            .map { it.listingId }
    }

    /**
     * Keeps [review] if it is of the latest version (a slow review of v1 must not land on v2). A
     * `block` verdict hides the listing, which puts it in the moderation queue for the owner.
     */
    suspend fun applyReview(listingId: String, review: ListingReview): Boolean = mutex.withLock {
        val listing = listings[listingId] ?: return@withLock false
        if (review.version != listing.latestVersion) return@withLock false
        val status = if (review.verdict == ReviewVerdict.BLOCK) ListingStatus.HIDDEN else listing.status
        save(listing.copy(review = review, status = status))
        true
    }

    suspend fun attachCover(listingId: String, bytes: ByteArray, mediaType: String): Boolean = mutex.withLock {
        val listing = listings[listingId] ?: return@withLock false
        if (mediaType !in COVER_TYPES || bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return@withLock false
        val file = File(dir(listingId), COVER_FILE)
        file.writeBytes(bytes)
        save(listing.copy(cover = StoredCover(mediaType, bytes.size)))
        true
    }

    /** The cover bytes and their media type, for a visible listing that has one. */
    suspend fun cover(listingId: String): Pair<ByteArray, String>? = mutex.withLock {
        val listing = visible(listingId) ?: return@withLock null
        val cover = listing.cover ?: return@withLock null
        File(dir(listingId), COVER_FILE).takeIf { it.isFile }?.readBytes()?.let { it to cover.mediaType }
    }

    /** What smart search reads: listed only, most installed first. */
    suspend fun matchCandidates(kind: ListingKind?, limit: Int): List<ListingSummary> = mutex.withLock {
        listings.values
            .filter { it.status == ListingStatus.LISTED && (kind == null || it.kind == kind) }
            .sortedWith(order(ListingSort.POPULAR))
            .take(limit)
            .map { it.toSummary() }
    }

    /** The cached translation of [listingId] into [lang], whatever text it was made from. */
    suspend fun translation(listingId: String, lang: String): StoredTranslation? = mutex.withLock {
        if (visible(listingId) == null) return@withLock null
        File(translationsDir(listingId), "$lang.json").takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString(StoredTranslation.serializer(), it.readText()) }.getOrNull() }
    }

    /** One file per language, overwritten on change, so a listing never holds more than [MAX_LANGS]. */
    suspend fun saveTranslation(listingId: String, lang: String, stored: StoredTranslation): Boolean = mutex.withLock {
        if (listings[listingId] == null) return@withLock false
        val dir = translationsDir(listingId)
        val file = File(dir, "$lang.json")
        if (!file.isFile && (dir.list()?.count { it.endsWith(".json") } ?: 0) >= MAX_LANGS) return@withLock false
        atomicWrite(file, json.encodeToString(StoredTranslation.serializer(), stored))
        true
    }

    private fun translationsDir(listingId: String) = File(dir(listingId), "translations")

    private fun networksOf(reports: List<StoredReport>): Int = reports.map { it.reporterKey }.distinct().size

    private fun visible(listingId: String): StoredListing? =
        listings[listingId]?.takeIf { it.status != ListingStatus.HIDDEN }

    /** The listing if [editKey] opens it, else null; [denied] then tells NotFound from Forbidden. */
    private fun editable(listingId: String, editKey: String?): StoredListing? {
        val listing = visible(listingId) ?: return null
        return listing.takeIf { editKey != null && secretMatches(it.editKeyHash, sha256Hex(editKey)) }
    }

    private fun denied(listingId: String): EditResult =
        if (visible(listingId) == null) EditResult.NotFound else EditResult.Forbidden

    private fun matches(listing: StoredListing, q: String): Boolean =
        listing.title.lowercase().contains(q) ||
            listing.summary.lowercase().contains(q) ||
            listing.publisherName.lowercase().contains(q) ||
            listing.tags.any { it.contains(q) }

    private fun order(sort: ListingSort): Comparator<StoredListing> = when (sort) {
        ListingSort.NEW -> compareByDescending<StoredListing> { it.createdAt }
        ListingSort.POPULAR -> compareByDescending<StoredListing> { it.installIds.size }.thenByDescending { it.createdAt }
    }

    private fun buildVersion(
        version: Int,
        changelog: String?,
        skill: SkillPackage?,
        agent: AgentPackage?,
        at: Long,
    ): MarketVersionView {
        val cleanSkill = skill?.let(MarketValidator::normalize)
        val cleanAgent = agent?.let(MarketValidator::normalize)
        val hash = JobStore.sha256Hex(MarketJson.encode(cleanSkill, cleanAgent).toByteArray())
        return MarketVersionView(version, changelog?.trim()?.takeIf { it.isNotEmpty() }, hash, cleanSkill, cleanAgent, at)
    }

    private fun dir(listingId: String) = File(root, listingId)

    private fun save(listing: StoredListing) {
        revision++
        listings[listing.listingId] = listing
        atomicWrite(File(dir(listing.listingId), LISTING_FILE), json.encodeToString(StoredListing.serializer(), listing))
    }

    private fun writeVersion(listingId: String, version: MarketVersionView) =
        atomicWrite(File(dir(listingId), "v${version.version}.json"), json.encodeToString(MarketVersionView.serializer(), version))

    private fun readVersion(listingId: String, version: Int): MarketVersionView? =
        File(dir(listingId), "v$version.json").takeIf { it.isFile }
            ?.let { runCatching { json.decodeFromString<MarketVersionView>(it.readText()) }.getOrNull() }

    private fun atomicWrite(target: File, text: String) {
        target.parentFile.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun reporterKey(reporter: String): String = sha256Hex("$salt:$reporter").take(REPORTER_KEY_CHARS)

    /** A per-install secret, so a stored reporter key can't be reversed by hashing all IPv4 addresses. */
    private fun loadOrCreateSalt(): String {
        val file = File(root, SALT_FILE)
        file.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = newEditKey()
        atomicWrite(file, fresh)
        return fresh
    }

    private fun newId(): String = "lst_" + UUID.randomUUID().toString().replace("-", "").take(12)

    private fun newEditKey(): String =
        ByteArray(EDIT_KEY_BYTES).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    companion object {
        private const val LISTING_FILE = "listing.json"
        private const val EDIT_KEY_BYTES = 32
        private const val SALT_FILE = "reporter-salt"
        private const val COVER_FILE = "cover.img"
        private const val MAX_LANGS = 20
        private const val MAX_COVER_BYTES = 8 * 1024 * 1024
        private val COVER_TYPES = setOf("image/png", "image/jpeg", "image/webp")
        private const val REPORTER_KEY_CHARS = 16

        fun sha256Hex(text: String): String = JobStore.sha256Hex(text.toByteArray())
    }
}
