package dev.amuse.forge.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import java.security.MessageDigest

// @trace cloud-forged-screens-40d702
// @trace skill-market-5d1e7a

/**
 * Checks every route of the gateway shares: body size, who is calling (for rate limits), and
 * secret comparison. Each `respond...` helper answers the call itself and returns false, so a route
 * reads `if (!bodyFits(...)) return@post`.
 */

/**
 * Refuses a body without a declared length or over [maxBytes] before a byte of it is read. Both
 * clients (the app and the worker) are Ktor and always send `Content-Length`; a chunked body would
 * otherwise slip past any size check.
 */
suspend fun RoutingContext.bodyFits(maxBytes: Long): Boolean {
    val length = call.request.contentLength()
    return when {
        length == null -> {
            call.respondText("Content-Length is required", status = HttpStatusCode.LengthRequired)
            false
        }
        length > maxBytes -> {
            call.respondText("Body over $maxBytes bytes", status = HttpStatusCode.PayloadTooLarge)
            false
        }
        else -> true
    }
}

suspend fun RoutingContext.allowedBy(limiter: RateLimiter, trustFlyClientIp: Boolean): Boolean {
    if (limiter.tryAcquire(call.clientKey(trustFlyClientIp))) return true
    call.respondText("Too many requests, try again later", status = HttpStatusCode.TooManyRequests)
    return false
}

/** On Fly the proxy sets `Fly-Client-IP`; anywhere else that header is the caller's to forge. */
fun ApplicationCall.clientKey(trustFlyClientIp: Boolean): String =
    (if (trustFlyClientIp) request.headers["Fly-Client-IP"] else null) ?: request.origin.remoteHost

/** Constant-time: how much of a guess was right must not show in how long the answer takes. */
fun secretMatches(expected: String, given: String): Boolean =
    expected.isNotEmpty() && MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
