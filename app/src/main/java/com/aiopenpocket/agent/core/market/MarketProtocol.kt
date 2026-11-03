package com.aiopenpocket.agent.core.market

// @trace skill-from-the-market-in-the-agent-builder-acd66a

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The app's mirror of the market protocol (`forge/contract/.../MarketProtocol.kt`), hand-written like
 * `ForgeProtocol.kt` because the forge is a separate Gradle build. Only the part the app reads today:
 * skill listings, their detail with the AI review, and the install counter. Field names and the
 * lowercase enum strings are the contract. An agent package inside a version is skipped on decode
 * (`ignoreUnknownKeys`), not mirrored: the builder installs skills, not whole agents.
 */
@Serializable
enum class MarketListingKind {
  @SerialName("skill") SKILL,
  @SerialName("agent") AGENT,
}

@Serializable
enum class MarketListingStatus {
  @SerialName("listed") LISTED,
  @SerialName("unlisted") UNLISTED,
  @SerialName("hidden") HIDDEN,
}

/** The gateway's AI review verdict on a version. `block` never reaches the app: such listings are hidden. */
@Serializable
enum class MarketReviewVerdict {
  @SerialName("safe") SAFE,
  @SerialName("caution") CAUTION,
  @SerialName("block") BLOCK,
}

@Serializable
data class MarketReviewConcern(
  val kind: String,
  /** The words in the package that raised it, so the person can find them in the instructions below. */
  val quote: String,
  val explanation: String,
)

/** What Muse Spark said about one version; it vouches only for the version it names. */
@Serializable
data class MarketListingReview(
  val version: Int,
  val verdict: MarketReviewVerdict,
  val summary: String,
  val capabilities: List<String> = emptyList(),
  val concerns: List<MarketReviewConcern> = emptyList(),
  val model: String = "",
  val reviewedAt: Long = 0,
)

/** A shared skill: the recipe only, no local ids, memory or chats. */
@Serializable
data class MarketSkillPackage(
  /** Suggested local id; the app renames it on a collision (see `MarketSkillImport`). */
  val skillId: String,
  val name: String,
  val description: String,
  val whenToUse: String,
  val instructionsMarkdown: String,
)

/** A search card: no package body. */
@Serializable
data class MarketListingSummary(
  val listingId: String,
  val kind: MarketListingKind,
  val title: String,
  val summary: String,
  val tags: List<String> = emptyList(),
  val publisherName: String = "",
  val latestVersion: Int = 1,
  val installs: Int = 0,
  val status: MarketListingStatus = MarketListingStatus.LISTED,
  val createdAt: Long = 0,
  val updatedAt: Long = 0,
  /** Gateway-relative URL of the Muse Image cover, once drawn; see [MarketClient.absoluteUrl]. */
  val coverUrl: String? = null,
  /** The latest version's review verdict; null while pending or when the gateway runs without a Meta key. */
  val safety: MarketReviewVerdict? = null,
)

@Serializable
data class MarketListingPage(
  val items: List<MarketListingSummary>,
  val total: Int,
  val page: Int,
  val limit: Int,
)

@Serializable
data class MarketVersionView(
  val version: Int,
  val changelog: String? = null,
  /** Hex SHA-256 of the package as compact JSON; not verified by the app yet. */
  val sha256: String = "",
  val skill: MarketSkillPackage? = null,
  val createdAt: Long = 0,
)

@Serializable
data class MarketListingDetail(
  val listing: MarketListingSummary,
  val version: MarketVersionView,
  /** The review of [version]; null while pending, when it failed, or when AI is off on the gateway. */
  val review: MarketListingReview? = null,
)

@Serializable
data class MarketInstallRequest(val installId: String)
