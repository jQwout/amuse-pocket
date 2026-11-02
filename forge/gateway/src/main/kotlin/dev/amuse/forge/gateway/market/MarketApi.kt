package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.InstallRequest
import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingSort
import dev.amuse.forge.contract.MarketIssue
import dev.amuse.forge.contract.MarketIssues
import dev.amuse.forge.contract.MarketRoutes
import dev.amuse.forge.contract.PublishListingRequest
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.contract.ReportRequest
import dev.amuse.forge.contract.UpdateListingRequest
import dev.amuse.forge.gateway.RateLimiter
import dev.amuse.forge.gateway.allowedBy
import dev.amuse.forge.gateway.bodyFits
import dev.amuse.forge.gateway.clientKey
import dev.amuse.forge.gateway.market.ai.MarketAi
import dev.amuse.forge.gateway.market.ai.MarketTranslations
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route

// @trace skill-market-5d1e7a

/** Abuse brakes for the market; the defaults are the ones in `docs/market-api.md`. */
data class MarketLimits(
    val maxBodyBytes: Long = 256L * 1024,
    val publishPerHour: Int = 10,
    val reportsPerHour: Int = 30,
    val installsPerHour: Int = 60,
    val trustFlyClientIp: Boolean = false,
    val maxPageSize: Int = 50,
)

private const val HOUR_MILLIS = 60 * 60 * 1000L
private val LISTING_ID = Regex("lst_[0-9a-f]{12}")
private val INSTALL_ID = Regex("[A-Za-z0-9_-]{8,64}")

/**
 * What anyone may do: browse, install, report. Public because the forge token lives only in the
 * Settings of the few who typed it, judges excluded; a market behind it would be empty for nearly
 * everyone. Installs and reports are rate limited per client instead.
 */
fun Route.marketPublicApi(store: MarketStore, limits: MarketLimits, ai: MarketAi? = null) {
    val installLimiter = RateLimiter(limits.installsPerHour, HOUR_MILLIS)
    val reportLimiter = RateLimiter(limits.reportsPerHour, HOUR_MILLIS)

    route(MarketRoutes.LISTINGS) {
        get { search(store, limits, ai) }

        route("/{id}") {
            get {
                val lang = langParam() ?: return@get
                val detail = listingId()?.let { store.get(it) } ?: return@get notFound()
                val translation = lang.value?.let { ai?.translations?.forDetail(detail, it, call.clientKey(limits.trustFlyClientIp)) }
                call.respond(detail.copy(translation = translation))
            }

            get("/versions/{version}") {
                val id = listingId() ?: return@get notFound()
                val number = call.parameters["version"]?.toIntOrNull() ?: return@get notFound()
                call.respond(store.version(id, number) ?: return@get notFound())
            }

            post("/install") {
                val id = listingId() ?: return@post notFound()
                if (!bodyFits(limits.maxBodyBytes)) return@post
                if (!allowedBy(installLimiter, limits.trustFlyClientIp)) return@post
                val request = call.receive<InstallRequest>()
                if (rejected(installIdIssues(request.installId))) return@post
                if (store.install(id, request.installId)) call.respond(HttpStatusCode.NoContent) else notFound()
            }

            post("/report") {
                val id = listingId() ?: return@post notFound()
                if (!bodyFits(limits.maxBodyBytes)) return@post
                if (!allowedBy(reportLimiter, limits.trustFlyClientIp)) return@post
                val request = call.receive<ReportRequest>()
                val noteIssues = if ((request.note?.length ?: 0) > MarketValidator.MAX_CHANGELOG) {
                    listOf(MarketIssue("note", "too_long", "note is too long"))
                } else emptyList()
                if (rejected(installIdIssues(request.installId) + noteIssues)) return@post
                val reporter = call.clientKey(limits.trustFlyClientIp)
                val known = store.report(id, request.installId, reporter, request.reason, request.note?.trim())
                if (known) call.respond(HttpStatusCode.NoContent) else notFound()
            }
        }
    }
}

/** Publishing and editing; mount it inside the client-token block. */
fun Route.marketApi(store: MarketStore, limits: MarketLimits, ai: MarketAi? = null) {
    val publishLimiter = RateLimiter(limits.publishPerHour, HOUR_MILLIS)

    route(MarketRoutes.LISTINGS) {
        post {
            if (!bodyFits(limits.maxBodyBytes)) return@post
            if (!allowedBy(publishLimiter, limits.trustFlyClientIp)) return@post
            val request = call.receive<PublishListingRequest>()
            if (rejected(MarketValidator.validatePublish(request))) return@post
            val published = store.publish(request)
            ai?.enricher?.published(published.listingId)
            call.respond(HttpStatusCode.Created, published)
        }

        route("/{id}") {
            patch {
                val id = listingId() ?: return@patch notFound()
                if (!bodyFits(limits.maxBodyBytes)) return@patch
                val request = call.receive<UpdateListingRequest>()
                if (rejected(MarketValidator.validateUpdate(request))) return@patch
                respondEdit(store.update(id, call.editKey(), request), HttpStatusCode.OK)
            }

            post("/versions") {
                val id = listingId() ?: return@post notFound()
                if (!bodyFits(limits.maxBodyBytes)) return@post
                if (!allowedBy(publishLimiter, limits.trustFlyClientIp)) return@post
                val listing = store.get(id) ?: return@post notFound()
                val request = call.receive<PublishVersionRequest>()
                if (rejected(MarketValidator.validateVersion(listing.listing.kind, request))) return@post
                val result = store.addVersion(id, call.editKey(), request)
                if (result is EditResult.Ok) ai?.enricher?.published(id)
                respondEdit(result, HttpStatusCode.Created)
            }
        }
    }
}

/** `GET /m/{id}`: a public page for a shared link, opening the app where it is installed. */
fun Route.marketSharePage(store: MarketStore, publicBaseUrl: String? = null) {
    get("${MarketRoutes.SHARE}/{id}") {
        val id = listingId() ?: return@get notFound()
        val detail = store.get(id) ?: return@get notFound()
        val image = publicBaseUrl?.let { base -> detail.listing.coverUrl?.let { base.trimEnd('/') + it } }
        call.respondText(SharePage.render(id, detail.listing, image), ContentType.Text.Html)
    }
}

private suspend fun RoutingContext.search(store: MarketStore, limits: MarketLimits, ai: MarketAi?) {
    val lang = langParam() ?: return
    val params = call.request.queryParameters
    val kind = when (val raw = params["kind"]) {
        null, "" -> null
        "skill" -> ListingKind.SKILL
        "agent" -> ListingKind.AGENT
        else -> return badQuery("kind", "kind must be skill or agent, not '$raw'")
    }
    val sort = when (val raw = params["sort"]) {
        null, "", "new" -> ListingSort.NEW
        "popular" -> ListingSort.POPULAR
        else -> return badQuery("sort", "sort must be new or popular, not '$raw'")
    }
    val page = (params["page"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
    val limit = (params["limit"]?.toIntOrNull() ?: DEFAULT_PAGE_SIZE).coerceIn(1, limits.maxPageSize)
    val query = params["q"]?.take(MarketValidator.MAX_TITLE)
    val found = store.search(kind, query, params["tag"]?.takeIf { it.isNotBlank() }, sort, page, limit)
    val translated = lang.value?.let { l -> ai?.translations?.forCards(found.items, l) } ?: found.items
    call.respond(found.copy(items = translated))
}

private const val DEFAULT_PAGE_SIZE = 20

/** `?lang=` as given (null value = none asked); null result = it was bad and a 400 has been sent. */
private class Lang(val value: String?)

private suspend fun RoutingContext.langParam(): Lang? {
    val raw = call.request.queryParameters["lang"]?.takeIf { it.isNotBlank() } ?: return Lang(null)
    if (MarketTranslations.LANG.matches(raw)) return Lang(raw)
    badQuery("lang", "lang must be a two-letter lowercase code like 'en', not '$raw'")
    return null
}

internal fun RoutingContext.listingId(): String? = call.parameters["id"]?.takeIf { LISTING_ID.matches(it) }

private fun ApplicationCall.editKey(): String? = request.headers[MarketRoutes.EDIT_KEY_HEADER]

private fun installIdIssues(installId: String): List<MarketIssue> =
    if (INSTALL_ID.matches(installId)) emptyList()
    else listOf(MarketIssue("installId", "bad_id", "installId must match ${INSTALL_ID.pattern}"))

private suspend fun RoutingContext.rejected(issues: List<MarketIssue>): Boolean {
    if (issues.isEmpty()) return false
    call.respond(HttpStatusCode.BadRequest, MarketIssues(issues))
    return true
}

private suspend fun RoutingContext.respondEdit(result: EditResult, okStatus: HttpStatusCode) = when (result) {
    is EditResult.Ok -> call.respond(okStatus, result.detail)
    EditResult.NotFound -> notFound()
    EditResult.Forbidden -> call.respondText("Wrong or missing ${MarketRoutes.EDIT_KEY_HEADER}", status = HttpStatusCode.Forbidden)
}

private suspend fun RoutingContext.badQuery(field: String, message: String) =
    call.respond(HttpStatusCode.BadRequest, MarketIssues(listOf(MarketIssue(field, "bad_query", message))))

internal suspend fun RoutingContext.notFound() = call.respondText("No such listing", status = HttpStatusCode.NotFound)

