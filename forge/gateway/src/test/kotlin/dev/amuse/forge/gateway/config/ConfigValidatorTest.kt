package dev.amuse.forge.gateway.config

import dev.amuse.forge.contract.RemoteConfig
import dev.amuse.forge.contract.RemoteModel
import dev.amuse.forge.contract.RemoteNotice
import dev.amuse.forge.contract.RemotePrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigValidatorTest {

    private fun codes(config: RemoteConfig) = ConfigValidator.validate(config).map { it.code }.toSet()

    @Test
    fun `the default and a full valid config pass`() {
        assertEquals(emptyList(), ConfigValidator.validate(RemoteConfig()))
        assertEquals(emptyList(), ConfigValidator.validate(ConfigFixtures.full()))
    }

    @Test
    fun `an empty model list is refused, null keeps the bundled catalog`() {
        assertTrue("no_models" in codes(ConfigFixtures.full().copy(models = emptyList())))
        assertEquals(emptyList(), ConfigValidator.validate(ConfigFixtures.full().copy(models = null)))
    }

    @Test
    fun `models the vendor trains on never get in`() {
        val contributor = ConfigFixtures.model("muse-spark-1.3-contributor", 1)
        assertTrue("trains_on_user_data" in codes(ConfigFixtures.full().copy(models = listOf(contributor))))
    }

    @Test
    fun `model rules - names, priorities, tokens, sampling, price`() {
        val models = listOf(
            ConfigFixtures.model("muse-spark-1.3", 1),
            ConfigFixtures.model("muse-spark-1.3", 2),
            ConfigFixtures.model("muse-spark-1.2", 2).copy(maxTokens = 0),
            ConfigFixtures.model("Bad Name", 3).copy(temperature = 0.5, topP = 0.9),
            ConfigFixtures.model("muse-spark-1.1", 4).copy(price = RemotePrice(-1.0, 0.1, 1.0)),
        )
        assertEquals(
            setOf("duplicate_model", "duplicate_priority", "out_of_range", "bad_model_name", "temperature_and_top_p", "bad_price"),
            codes(ConfigFixtures.full().copy(models = models)),
        )
    }

    @Test
    fun `versions, links and feature keys`() {
        val bad = ConfigFixtures.full().copy(
            minAppVersionCode = 5,
            latestAppVersionCode = 3,
            updateUrl = "http://example.com/app.apk",
            features = mapOf("Market!" to true),
        )
        assertEquals(setOf("min_above_latest", "not_https", "bad_feature_key"), codes(bad))
    }

    @Test
    fun `a notice needs english text, a sane length and an https link`() {
        val noEnglish = RemoteNotice(id = "n1", text = mapOf("ru" to "Привет"))
        assertTrue("missing_en" in codes(RemoteConfig(notice = noEnglish)))

        val long = RemoteNotice(id = "n1", text = mapOf("en" to "x".repeat(ConfigValidator.MAX_NOTICE_CHARS + 1)))
        assertTrue("too_long" in codes(RemoteConfig(notice = long)))

        val badLink = RemoteNotice(id = "bad id", text = mapOf("en" to "hi"), url = "javascript:alert(1)")
        assertEquals(setOf("bad_id", "not_https"), codes(RemoteConfig(notice = badLink)))
    }
}

object ConfigFixtures {
    fun model(name: String, priority: Int) = RemoteModel(
        name = name,
        priority = priority,
        maxTokens = 16_000,
        inputModalities = listOf("text", "image"),
        price = RemotePrice(input = 1.25, cachedInput = 0.15, output = 4.25),
    )

    fun full(version: Int = 1) = RemoteConfig(
        configVersion = version,
        minAppVersionCode = 1,
        latestAppVersionCode = 2,
        updateUrl = "https://github.com/jQwout/amuse-pocket/releases",
        models = listOf(model("muse-spark-1.3", 1), model("muse-wonder-1.0", 2)),
        features = mapOf("market" to true, "forge" to false),
        notice = RemoteNotice(id = "judging-2026", text = mapOf("en" to "Welcome, judges!", "ru" to "Привет")),
    )
}
