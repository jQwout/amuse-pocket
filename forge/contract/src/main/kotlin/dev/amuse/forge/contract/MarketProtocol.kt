package dev.amuse.forge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// @trace skill-market-5d1e7a

/**
 * The market protocol: people publish an agent or a custom skill as a package, others find it and
 * install it. The design and the reasons behind it are in `docs/market-api.md`.
 *
 * A package is the recipe only, never the person's data: no local ids, memory, chats or hooks.
 * Collections travel as sorted, distinct lists so the same package always encodes to the same
 * bytes and its [MarketVersionView.sha256] can be checked by the app.
 */
@Serializable
enum class ListingKind {
    @SerialName("skill") SKILL,
    @SerialName("agent") AGENT,
}

@Serializable
enum class ListingStatus {
    /** In search and reachable by link. */
    @SerialName("listed") LISTED,
    /** Reachable by link only. */
    @SerialName("unlisted") UNLISTED,
    /** Taken down by reports; gone for everyone until a human looks. */
    @SerialName("hidden") HIDDEN,
}

@Serializable
enum class ListingSort {
    @SerialName("new") NEW,
    @SerialName("popular") POPULAR,
}

@Serializable
enum class ReportReason {
    @SerialName("prompt_injection") PROMPT_INJECTION,
    @SerialName("harmful") HARMFUL,
    @SerialName("spam") SPAM,
    @SerialName("broken") BROKEN,
    @SerialName("other") OTHER,
}

/** Mirrors the app's `AgentMode`; kept as its own enum so the contract does not depend on the app. */
@Serializable
enum class MarketAgentMode { CHAT, PLAN, GOAL, REFLECT, TUTOR }

@Serializable
data class SkillPackage(
    /** Suggested local id; the app may rename it on a collision. */
    val skillId: String,
    val name: String,
    val description: String,
    val whenToUse: String,
    val instructionsMarkdown: String,
)

@Serializable
data class AgentPackage(
    val name: String,
    val description: String,
    val iconKey: String = "",
    val systemPrompt: String,
    /** Format-checked only: the app is the authority on which tools exist. */
    val toolNames: List<String> = emptyList(),
    val builtInSkillIds: List<String> = emptyList(),
    val customSkills: List<SkillPackage> = emptyList(),
    val defaultMode: MarketAgentMode = MarketAgentMode.CHAT,
    val allowedModes: List<MarketAgentMode> = listOf(MarketAgentMode.CHAT),
    val includeSharedProfile: Boolean = false,
    /** `WONDER`, `SPARK` or null for the app's automatic choice. */
    val model: String? = null,
    val goalMaxRounds: Int? = null,
)

/** `POST /v1/market/listings` - publish version 1. Exactly one of [skill] / [agent], matching [kind]. */
@Serializable
data class PublishListingRequest(
    val kind: ListingKind,
    val title: String,
    val summary: String,
    val tags: List<String> = emptyList(),
    val publisherName: String,
    val changelog: String? = null,
    val skill: SkillPackage? = null,
    val agent: AgentPackage? = null,
)

/** The only time the edit key travels: the server keeps its hash, the app keeps the key. */
@Serializable
data class PublishListingResponse(val listingId: String, val version: Int, val editKey: String)

/** `POST /v1/market/listings/{id}/versions` - a new version of the same kind. Needs `X-Edit-Key`. */
@Serializable
data class PublishVersionRequest(
    val changelog: String? = null,
    val skill: SkillPackage? = null,
    val agent: AgentPackage? = null,
)

/** `PATCH /v1/market/listings/{id}` - null fields stay as they are. Needs `X-Edit-Key`. */
@Serializable
data class UpdateListingRequest(
    val title: String? = null,
    val summary: String? = null,
    val tags: List<String>? = null,
    /** `listed` or `unlisted`; `hidden` is not the publisher's to set or lift. */
    val status: ListingStatus? = null,
)

@Serializable
data class InstallRequest(val installId: String)

@Serializable
data class ReportRequest(val installId: String, val reason: ReportReason, val note: String? = null)

/** The AI review's call on a package; see `docs/market-api.md`, "AI features". */
@Serializable
enum class ReviewVerdict {
    /** Does what it says, nothing risky found. */
    @SerialName("safe") SAFE,
    /** Powerful tools or something worth reading before install; listed with a warning. */
    @SerialName("caution") CAUTION,
    /** Looks built to harm or deceive; hidden until the owner looks. */
    @SerialName("block") BLOCK,
}

@Serializable
data class ReviewConcern(
    /** Short slug, e.g. `prompt_injection`, `data_exfiltration`, `scope_mismatch`, `heuristic`. */
    val kind: String,
    /** The words in the package that raised it, so the person can find them. */
    val quote: String,
    val explanation: String,
)

/** What the reviewer found in one version; shown on the install screen. */
@Serializable
data class ListingReview(
    val version: Int,
    val verdict: ReviewVerdict,
    /** What the agent or skill really does, in plain words. */
    val summary: String,
    val capabilities: List<String> = emptyList(),
    val concerns: List<ReviewConcern> = emptyList(),
    val model: String,
    val reviewedAt: Long,
)

/** What search shows: no package body. */
@Serializable
data class ListingSummary(
    val listingId: String,
    val kind: ListingKind,
    val title: String,
    val summary: String,
    val tags: List<String>,
    val publisherName: String,
    val latestVersion: Int,
    val installs: Int,
    val status: ListingStatus,
    val createdAt: Long,
    val updatedAt: Long,
    /** Gateway-relative URL of the Muse Image cover, once it is drawn. */
    val coverUrl: String? = null,
    /** The latest version's review verdict; null while it is pending or when AI is off. */
    val safety: ReviewVerdict? = null,
    /** Title and summary in the language search asked for (`?lang=`), once translated. */
    val translation: CardText? = null,
)

/** A listing card in another language. */
@Serializable
data class CardText(val lang: String, val title: String, val summary: String)

/** The review's own words, translated; concern quotes stay original so they still match the package. */
@Serializable
data class ReviewText(
    val summary: String,
    val capabilities: List<String> = emptyList(),
    /** Same order as [ListingReview.concerns]. */
    val concernExplanations: List<String> = emptyList(),
)

/**
 * A machine translation of one listing version, shown next to the original, never instead of it.
 * The package's structure (tools, skills, modes, model, ids) is always the original's; only its text
 * is translated. [agent]/[skill] are null when the package was too long or the translated text failed
 * the same pattern scan the original passed: then only the card and the review are translated.
 */
@Serializable
data class ListingTranslation(
    val lang: String,
    val sourceLang: String,
    val title: String,
    val summary: String,
    val review: ReviewText? = null,
    val skill: SkillPackage? = null,
    val agent: AgentPackage? = null,
    val model: String,
    val translatedAt: Long,
)

@Serializable
data class MarketVersionView(
    val version: Int,
    val changelog: String? = null,
    /** Hex SHA-256 of the package encoded as compact JSON (see [MarketJson]). */
    val sha256: String,
    val skill: SkillPackage? = null,
    val agent: AgentPackage? = null,
    val createdAt: Long,
)

@Serializable
data class ListingDetail(
    val listing: ListingSummary,
    val version: MarketVersionView,
    /** The review of [version]; null while pending, when it failed, or when AI is off. */
    val review: ListingReview? = null,
    /** With `?lang=`: [version] in that language; null when it already is, when AI is off, or on failure. */
    val translation: ListingTranslation? = null,
)

/** `POST /v1/market/match` - find listings for a need described in plain words. */
@Serializable
data class MatchRequest(val query: String, val kind: ListingKind? = null)

@Serializable
data class MatchItem(val listing: ListingSummary, /** Why it fits, in the language of the query. */ val reason: String)

@Serializable
data class MatchResponse(val items: List<MatchItem>)

@Serializable
data class ListingPage(val items: List<ListingSummary>, val total: Int, val page: Int, val limit: Int)

/** One problem with a request; every problem is reported at once, the way the app's validator does. */
@Serializable
data class MarketIssue(val field: String, val code: String, val message: String)

@Serializable
data class MarketIssues(val issues: List<MarketIssue>)

/**
 * The one encoding the package hash is computed over: compact, defaults included, fields in
 * declaration order. The app must encode with the same settings to verify [MarketVersionView.sha256].
 */
object MarketJson {
    val canonical: Json = Json { encodeDefaults = true; explicitNulls = true; prettyPrint = false }

    fun encode(skill: SkillPackage?, agent: AgentPackage?): String = when {
        agent != null -> canonical.encodeToString(AgentPackage.serializer(), agent)
        skill != null -> canonical.encodeToString(SkillPackage.serializer(), skill)
        else -> ""
    }
}

/** What the moderation queue shows. */
@Serializable
enum class ModerationFilter {
    /** Every listing with at least one report, hidden or not. */
    @SerialName("reported") REPORTED,
    /** Every hidden listing, reported, blocked by the AI review, or hidden by hand. */
    @SerialName("hidden") HIDDEN,
}

/** One report as the owner sees it: no device id, no network, just what was said and when. */
@Serializable
data class ModerationReport(val reason: ReportReason, val note: String? = null, val at: Long)

@Serializable
data class ModerationItem(
    val listing: ListingSummary,
    /** How many distinct networks reported it: the number the auto-hide counts. */
    val reportNetworks: Int,
    val reports: List<ModerationReport>,
    /** The AI review, the reason a listing is hidden when nobody reported it. */
    val review: ListingReview? = null,
)

@Serializable
data class ModerationQueue(val items: List<ModerationItem>)

object MarketRoutes {
    const val LISTINGS = "/v1/market/listings"
    const val SHARE = "/m"
    const val EDIT_KEY_HEADER = "X-Edit-Key"

    fun listing(id: String) = "$LISTINGS/$id"
    fun version(id: String, version: Int) = "$LISTINGS/$id/versions/$version"
    fun versions(id: String) = "$LISTINGS/$id/versions"
    fun install(id: String) = "$LISTINGS/$id/install"
    fun report(id: String) = "$LISTINGS/$id/report"
    fun share(id: String) = "$SHARE/$id"
    fun cover(id: String) = "$LISTINGS/$id/cover"
    const val MATCH = "/v1/market/match"
    fun deepLink(id: String) = "aiopenpocket://market/$id"

    const val ADMIN_LISTINGS = "/v1/admin/market/listings"
    fun adminListing(id: String) = "$ADMIN_LISTINGS/$id"
    fun adminRestore(id: String) = "$ADMIN_LISTINGS/$id/restore"
    fun adminHide(id: String) = "$ADMIN_LISTINGS/$id/hide"
}
