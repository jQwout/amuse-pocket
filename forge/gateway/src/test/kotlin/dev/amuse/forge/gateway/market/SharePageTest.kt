package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.contract.ListingSummary
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharePageTest {
    private val listing = ListingSummary("lst_0123456789ab", ListingKind.AGENT, "Buddy", "Helps", emptyList(), "Uros", 1, 0, ListingStatus.LISTED, 1, 1)

    @Test
    fun `the preview image is escaped and announced as a large card`() {
        val html = SharePage.render("lst_0123456789ab", listing, "https://x.example/c?a=1&b=\"><script>")
        assertTrue("""content="https://x.example/c?a=1&amp;b=&quot;&gt;&lt;script&gt;"""" in html)
        assertFalse("<script>" in html)
        assertTrue("summary_large_image" in html)
    }

    @Test
    fun `no image, no preview tags`() {
        assertFalse("og:image" in SharePage.render("lst_0123456789ab", listing))
    }
}
