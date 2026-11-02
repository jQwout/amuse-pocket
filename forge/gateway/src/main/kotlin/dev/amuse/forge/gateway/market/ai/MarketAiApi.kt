package dev.amuse.forge.gateway.market.ai

import dev.amuse.forge.contract.MarketIssue
import dev.amuse.forge.contract.MarketIssues
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.MatchRequest
import dev.amuse.forge.contract.MatchResponse
import dev.amuse.forge.gateway.allowedBy
import dev.amuse.forge.gateway.bodyFits
import dev.amuse.forge.gateway.market.MarketLimits
import dev.amuse.forge.gateway.market.MarketStore
import dev.amuse.forge.gateway.market.listingId
import dev.amuse.forge.gateway.market.notFound
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import org.slf4j.LoggerFactory

// @trace market-ai-8f3b21

private val log = LoggerFactory.getLogger("dev.amuse.forge.gateway.market.ai.MarketAiApi")

/** Covers never change once drawn, so caches may keep them for a day. */
private const val COVER_MAX_AGE_SECONDS = 24 * 60 * 60

/** The public, AI-backed part of the market: covers and smart search. [ai] null = no Meta key. */
fun Route.marketAiPublicApi(store: MarketStore, ai: MarketAi?, limits: MarketLimits) {
    get("${MarketRoutes.LISTINGS}/{id}/cover") {
        val id = listingId() ?: return@get notFound()
        val (bytes, mediaType) = store.cover(id) ?: return@get notFound()
        call.response.header(HttpHeaders.CacheControl, "public, max-age=$COVER_MAX_AGE_SECONDS")
        call.respondBytes(bytes, ContentType.parse(mediaType))
    }

    post(MarketRoutes.MATCH) {
        if (ai == null) {
            return@post call.respondText("Smart search is off on this server", status = HttpStatusCode.ServiceUnavailable)
        }
        if (!bodyFits(limits.maxBodyBytes)) return@post
        val request = call.receive<MatchRequest>()
        val query = request.query.trim()
        if (query.isEmpty() || query.length > ai.limits.maxQueryChars) {
            val issue = MarketIssue("query", "bad_query", "query must be 1..${ai.limits.maxQueryChars} characters")
            return@post call.respond(HttpStatusCode.BadRequest, MarketIssues(listOf(issue)))
        }
        if (!allowedBy(ai.matchLimiter, limits.trustFlyClientIp)) return@post
        if (!ai.globalMatchLimiter.tryAcquire("global")) {
            return@post call.respondText("Smart search is busy, try again later", status = HttpStatusCode.TooManyRequests)
        }
        val items = runCatching { ai.matcher.match(query, request.kind) }
            .getOrElse {
                log.warn("Smart search failed: {}", it.message)
                return@post call.respondText("Smart search is unavailable right now", status = HttpStatusCode.ServiceUnavailable)
            }
        call.respond(MatchResponse(items))
    }
}
