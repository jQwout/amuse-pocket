package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingSummary
import dev.amuse.forge.contract.MarketRoutes

// @trace skill-market-5d1e7a

/**
 * The page behind a shared link. Everything that came from a publisher is escaped: the title and
 * summary are strangers' text rendered in someone's browser.
 */
object SharePage {

    /** [imageUrl] is the absolute cover URL, for link previews; null leaves the preview text-only. */
    fun render(listingId: String, listing: ListingSummary, imageUrl: String? = null): String {
        val link = escape(MarketRoutes.deepLink(listingId))
        val title = escape(listing.title)
        val what = if (listing.kind == ListingKind.AGENT) "agent" else "skill"
        return """
            <!DOCTYPE html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>$title - AI Open Pocket</title>
            <meta property="og:title" content="$title">
            <meta property="og:description" content="${escape(listing.summary)}">
            ${imageMeta(imageUrl)}
            </head><body style="font-family:sans-serif;max-width:32rem;margin:3rem auto;padding:0 1rem">
            <p>An $what shared on AI Open Pocket</p>
            <h1>$title</h1>
            <p>${escape(listing.summary)}</p>
            <p>by ${escape(listing.publisherName)} &middot; ${listing.installs} installs</p>
            <p><a href="$link">Open in AI Open Pocket</a></p>
            </body></html>
        """.trimIndent()
    }

    private fun imageMeta(imageUrl: String?): String = imageUrl?.let {
        """<meta property="og:image" content="${escape(it)}"><meta name="twitter:card" content="summary_large_image">"""
    }.orEmpty()

    fun escape(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }
}
