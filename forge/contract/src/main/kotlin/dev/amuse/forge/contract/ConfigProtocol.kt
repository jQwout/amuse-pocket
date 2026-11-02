package dev.amuse.forge.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// @trace remote-config-2403cd

/**
 * Remote config: what the app may change without a release. Read by every app with no token
 * (most installs have no forge token, judges included), written by the owner with the admin token.
 *
 * Every field is optional in effect: an empty or missing value means "keep what the app ships
 * with". A broken or unreachable config must never break the app; it falls back to its assets.
 */
@Serializable
data class RemoteConfig(
    /** Grows with every change; the app keeps the highest it has seen and ignores an older one. */
    val configVersion: Int = 0,
    /** Below this `versionCode` the app asks the person to update before going on. */
    val minAppVersionCode: Int = 0,
    /** Below this the app shows a soft "update available". */
    val latestAppVersionCode: Int = 0,
    val updateUrl: String? = null,
    /** Replaces the bundled `models_meta.json` when not null. Null keeps the bundled catalog. */
    val models: List<RemoteModel>? = null,
    /** Kill switches, e.g. `market`, `forge`, `web_search`. A missing key keeps the app's default. */
    val features: Map<String, Boolean> = emptyMap(),
    val notice: RemoteNotice? = null,
)

/** One Meta model, as `models_meta.json` describes it plus the price the app needs for its cost card. */
@Serializable
data class RemoteModel(
    val name: String,
    val priority: Int,
    val maxTokens: Int,
    val reasoning: Boolean = true,
    val supportsTools: Boolean = true,
    val inputModalities: List<String> = emptyList(),
    val temperature: Double? = null,
    val topP: Double? = null,
    /** USD per 1M tokens: without it a new model's spend would show as unknown. */
    val price: RemotePrice,
)

@Serializable
data class RemotePrice(val input: Double, val cachedInput: Double, val output: Double)

@Serializable
enum class NoticeLevel {
    @SerialName("info") INFO,
    @SerialName("warning") WARNING,
}

/** A banner for everyone; [id] lets the app remember that the person closed this one. */
@Serializable
data class RemoteNotice(
    val id: String,
    val level: NoticeLevel = NoticeLevel.INFO,
    /** Text per UI language (`en`, `ru`, ...); the app falls back to `en`. */
    val text: Map<String, String>,
    val url: String? = null,
)

object ConfigRoutes {
    const val CONFIG = "/v1/config"
    const val ADMIN_CONFIG = "/v1/admin/config"
}
