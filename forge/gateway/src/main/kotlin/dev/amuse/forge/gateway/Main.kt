package dev.amuse.forge.gateway

import dev.amuse.forge.gateway.ai.HttpMetaModels
import dev.amuse.forge.gateway.config.ConfigStore
import dev.amuse.forge.gateway.market.MarketLimits
import dev.amuse.forge.gateway.market.MarketStore
import dev.amuse.forge.gateway.market.ai.MarketAi
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Entry point. Everything comes from the environment so the same jar runs on a laptop
 * (`./gradlew :gateway:run`) and on Fly.io (`fly secrets set ...`):
 *
 * - `PORT` (default 8080)
 * - `FORGE_DATA_DIR` (default `./data`; on Fly the mounted volume)
 * - `FORGE_CLIENT_TOKEN`, `FORGE_WORKER_TOKEN`, `FORGE_ADMIN_TOKEN` - empty means that side is locked out
 * - `FLY_APP_NAME` - set by Fly; then the market trusts the proxy's `Fly-Client-IP` for rate limits
 * - `META_API_KEY` - turns on the market's AI review, covers and smart search; empty leaves them off
 * - `META_BASE_URL` (default `https://api.meta.ai/v1`)
 * - `FORGE_PUBLIC_URL` - this gateway's public address, for link previews (e.g. `https://amuse-forge.fly.dev`)
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    val dataDir = File(System.getenv("FORGE_DATA_DIR") ?: "data")
    val tokens = ForgeTokens(
        client = env("FORGE_CLIENT_TOKEN").orEmpty(),
        worker = env("FORGE_WORKER_TOKEN").orEmpty(),
        admin = env("FORGE_ADMIN_TOKEN").orEmpty(),
    )
    if (tokens.client.isBlank()) System.err.println("forge gateway: FORGE_CLIENT_TOKEN is empty - the app side is locked out")
    if (tokens.worker.isBlank()) System.err.println("forge gateway: FORGE_WORKER_TOKEN is empty - the worker side is locked out")
    if (tokens.admin.isBlank()) System.err.println("forge gateway: FORGE_ADMIN_TOKEN is empty - remote config is read-only")
    val onFly = env("FLY_APP_NAME") != null
    val market = MarketStore(dataDir)
    val marketAi = marketAi(market)
    println("forge gateway: listening on :$port, data in ${dataDir.absolutePath}")
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        forgeModule(
            JobStore(dataDir),
            tokens,
            market = market,
            marketLimits = MarketLimits(trustFlyClientIp = onFly),
            forgeLimits = ForgeLimits(trustFlyClientIp = onFly),
            config = ConfigStore(dataDir),
            marketAi = marketAi,
            publicBaseUrl = env("FORGE_PUBLIC_URL"),
        )
    }.start(wait = true)
}

/** The market's AI side, or null without a key; it resumes whatever a restart left unreviewed. */
private fun marketAi(market: MarketStore): MarketAi? {
    val key = env("META_API_KEY") ?: run {
        System.err.println("forge gateway: META_API_KEY is empty - market review, covers and smart search are off")
        return null
    }
    val models = HttpMetaModels(baseUrl = env("META_BASE_URL") ?: "https://api.meta.ai/v1", apiKey = key)
    return MarketAi.create(market, models, CoroutineTaskRunner()).also { runBlocking { it.enricher.resumePending() } }
}

private fun env(name: String): String? = System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
