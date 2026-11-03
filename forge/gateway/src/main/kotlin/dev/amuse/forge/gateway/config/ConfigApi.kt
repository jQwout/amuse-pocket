package dev.amuse.forge.gateway.config

import dev.amuse.forge.contract.ConfigRoutes
import dev.amuse.forge.contract.MarketIssues
import dev.amuse.forge.contract.RemoteConfig
import dev.amuse.forge.gateway.bodyFits
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

// @trace remote-config-2403cd

/** How long an app may use its copy before asking again; short, since this is the kill switch. */
private const val MAX_AGE_SECONDS = 300

/** `GET /v1/config`, public: every install reads it, most have no token. */
fun Route.configRead(store: ConfigStore) {
    get(ConfigRoutes.CONFIG) {
        val (body, etag) = store.served()
        call.response.header(HttpHeaders.CacheControl, "public, max-age=$MAX_AGE_SECONDS")
        call.response.header(HttpHeaders.ETag, etag)
        if (call.request.headers[HttpHeaders.IfNoneMatch] == etag) {
            call.respond(HttpStatusCode.NotModified)
        } else {
            call.respondText(body, ContentType.Application.Json)
        }
    }
}

/** `PUT /v1/admin/config`; mount it inside the admin-token block. */
fun Route.configWrite(store: ConfigStore, maxBodyBytes: Long) {
    put(ConfigRoutes.ADMIN_CONFIG) {
        if (!bodyFits(maxBodyBytes)) return@put
        val next = call.receive<RemoteConfig>()
        val issues = ConfigValidator.validate(next)
        if (issues.isNotEmpty()) return@put call.respond(HttpStatusCode.BadRequest, MarketIssues(issues))
        when (val result = store.replace(next)) {
            is ReplaceResult.Ok -> call.respond(result.config)
            is ReplaceResult.Stale -> call.respondText(
                "configVersion must be above ${result.current.configVersion}",
                status = HttpStatusCode.Conflict,
            )
        }
    }
}
