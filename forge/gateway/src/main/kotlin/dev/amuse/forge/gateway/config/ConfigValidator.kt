package dev.amuse.forge.gateway.config

import dev.amuse.forge.contract.MarketIssue
import dev.amuse.forge.contract.RemoteConfig
import dev.amuse.forge.contract.RemoteModel
import dev.amuse.forge.contract.RemoteNotice

// @trace remote-config-2403cd

/**
 * Rules a remote config must pass before every app in the field picks it up. A bad config here is
 * worse than a bad release: it reaches every install within minutes, so the rules lean strict and
 * mirror the app's own (`ModelCatalog`: no contributor models, never temperature with top_p).
 */
object ConfigValidator {
    const val MAX_NOTICE_CHARS = 280
    const val MAX_MODELS = 10
    const val MAX_FEATURES = 32
    const val MAX_TOKENS_CEILING = 128_000

    private val MODEL_NAME = Regex("[a-z0-9][a-z0-9._-]{1,63}")
    private val FEATURE_KEY = Regex("[a-z][a-z0-9_]{0,31}")
    private val NOTICE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val LANG = Regex("[a-z]{2}")
    private const val CONTRIBUTOR_SUFFIX = "-contributor"

    fun validate(config: RemoteConfig): List<MarketIssue> = buildList {
        if (config.configVersion < 0) add(MarketIssue("configVersion", "out_of_range", "configVersion can't be negative"))
        if (config.minAppVersionCode > config.latestAppVersionCode && config.latestAppVersionCode > 0) {
            add(MarketIssue("minAppVersionCode", "min_above_latest", "minAppVersionCode is above latestAppVersionCode"))
        }
        config.updateUrl?.let { if (!isHttps(it)) add(MarketIssue("updateUrl", "not_https", "updateUrl must be an https link")) }
        config.models?.let { addAll(modelListIssues(it)) }
        addAll(featureIssues(config.features))
        config.notice?.let { addAll(noticeIssues(it)) }
    }

    private fun modelListIssues(models: List<RemoteModel>): List<MarketIssue> = buildList {
        if (models.isEmpty()) add(MarketIssue("models", "no_models", "Send null to keep the bundled catalog, not an empty list"))
        if (models.size > MAX_MODELS) add(MarketIssue("models", "too_many", "At most $MAX_MODELS models"))
        models.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach {
            add(MarketIssue("models", "duplicate_model", "Model '$it' is listed twice"))
        }
        models.groupBy { it.priority }.filterValues { it.size > 1 }.keys.forEach {
            add(MarketIssue("models", "duplicate_priority", "Two models share priority $it"))
        }
        models.forEachIndexed { i, model -> addAll(modelIssues("models[$i]", model)) }
    }

    private fun modelIssues(field: String, model: RemoteModel): List<MarketIssue> = buildList {
        if (!MODEL_NAME.matches(model.name)) add(MarketIssue("$field.name", "bad_model_name", "'${model.name}' is not a model id"))
        if (model.name.endsWith(CONTRIBUTOR_SUFFIX)) {
            add(MarketIssue("$field.name", "trains_on_user_data", "Contributor models train on the person's data"))
        }
        if (model.priority < 1) add(MarketIssue("$field.priority", "out_of_range", "priority starts at 1"))
        if (model.maxTokens !in 1..MAX_TOKENS_CEILING) {
            add(MarketIssue("$field.maxTokens", "out_of_range", "maxTokens must be 1..$MAX_TOKENS_CEILING"))
        }
        if (model.temperature != null && model.topP != null) {
            add(MarketIssue(field, "temperature_and_top_p", "Meta answers 400 to temperature and top_p together"))
        }
        val p = model.price
        if (listOf(p.input, p.cachedInput, p.output).any { it < 0 || it.isNaN() || it.isInfinite() }) {
            add(MarketIssue("$field.price", "bad_price", "Prices are USD per 1M tokens, zero or more"))
        }
    }

    private fun featureIssues(features: Map<String, Boolean>): List<MarketIssue> = buildList {
        if (features.size > MAX_FEATURES) add(MarketIssue("features", "too_many", "At most $MAX_FEATURES features"))
        features.keys.filterNot { FEATURE_KEY.matches(it) }.forEach {
            add(MarketIssue("features", "bad_feature_key", "'$it' must match ${FEATURE_KEY.pattern}"))
        }
    }

    private fun noticeIssues(notice: RemoteNotice): List<MarketIssue> = buildList {
        if (!NOTICE_ID.matches(notice.id)) add(MarketIssue("notice.id", "bad_id", "notice.id must match ${NOTICE_ID.pattern}"))
        if (notice.text["en"].isNullOrBlank()) add(MarketIssue("notice.text", "missing_en", "English text is the fallback"))
        notice.text.forEach { (lang, text) ->
            if (!LANG.matches(lang)) add(MarketIssue("notice.text", "bad_lang", "'$lang' is not a two-letter language"))
            if (text.length > MAX_NOTICE_CHARS) add(MarketIssue("notice.text.$lang", "too_long", "At most $MAX_NOTICE_CHARS characters"))
        }
        notice.url?.let { if (!isHttps(it)) add(MarketIssue("notice.url", "not_https", "notice.url must be an https link")) }
    }

    private fun isHttps(url: String): Boolean =
        runCatching { java.net.URI(url) }.getOrNull()?.let { it.scheme == "https" && !it.host.isNullOrBlank() } ?: false
}
