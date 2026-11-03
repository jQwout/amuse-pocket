package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.MarketIssue
import dev.amuse.forge.contract.MarketIssues
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.ModerationFilter
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// @trace skill-market-5d1e7a

/**
 * The owner's side of the market; mount it inside the admin-token block. Auto-hide takes a listing
 * down "until a human looks": this is where the human looks.
 */
fun Route.marketModerationApi(store: MarketStore) {
    route(MarketRoutes.ADMIN_LISTINGS) {
        get {
            val filter = when (val raw = call.request.queryParameters["filter"]) {
                null, "", "reported" -> ModerationFilter.REPORTED
                "hidden" -> ModerationFilter.HIDDEN
                else -> return@get call.respond(
                    HttpStatusCode.BadRequest,
                    MarketIssues(listOf(MarketIssue("filter", "bad_query", "filter must be reported or hidden, not '$raw'"))),
                )
            }
            call.respond(store.moderationQueue(filter))
        }

        route("/{id}") {
            post("/restore") { act { store.restore(it) } }
            post("/hide") { act { store.hide(it) } }
            delete { act { store.remove(it) } }
        }
    }
}

private suspend fun RoutingContext.act(action: suspend (String) -> Boolean) {
    val id = listingId() ?: return notFound()
    if (action(id)) call.respond(HttpStatusCode.NoContent) else notFound()
}
