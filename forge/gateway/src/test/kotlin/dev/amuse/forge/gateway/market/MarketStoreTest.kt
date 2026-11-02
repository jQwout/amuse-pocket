package dev.amuse.forge.gateway.market

import dev.amuse.forge.contract.ListingKind
import dev.amuse.forge.contract.ListingSort
import dev.amuse.forge.contract.ListingStatus
import dev.amuse.forge.contract.MarketJson
import dev.amuse.forge.contract.PublishVersionRequest
import dev.amuse.forge.contract.ReportReason
import dev.amuse.forge.contract.UpdateListingRequest
import dev.amuse.forge.gateway.JobStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MarketStoreTest {

    private var clock = 1_000L
    private fun newDir(): File = Files.createTempDirectory("market-test").toFile()
    private fun store(dir: File = newDir()) = MarketStore(dir, now = { clock++ })

    @Test
    fun `a published listing comes back as version 1 with a verifiable hash`() = runBlocking<Unit> {
        val store = store()
        val published = store.publish(MarketFixtures.publishAgent())
        assertEquals(1, published.version)
        assertTrue(published.editKey.length >= 40)

        val detail = assertNotNull(store.get(published.listingId))
        assertEquals(ListingKind.AGENT, detail.listing.kind)
        assertEquals(ListingStatus.LISTED, detail.listing.status)
        val agent = assertNotNull(detail.version.agent)
        assertEquals(listOf("memory_tool", "notifications"), agent.toolNames, "the stored package is normalized")
        assertEquals(JobStore.sha256Hex(MarketJson.encode(null, agent).toByteArray()), detail.version.sha256)
    }

    @Test
    fun `the edit key is never written to disk, and everything survives a restart`() = runBlocking<Unit> {
        val dir = newDir()
        val published = store(dir).publish(MarketFixtures.publishSkill())
        val onDisk = dir.walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        assertFalse(published.editKey in onDisk)

        val reopened = store(dir)
        assertEquals("Water coach", reopened.get(published.listingId)?.listing?.title)
        assertIs<EditResult.Ok>(reopened.addVersion(published.listingId, published.editKey, PublishVersionRequest(skill = MarketFixtures.skill())))
    }

    @Test
    fun `only the edit key opens new versions, and old versions stay`() = runBlocking<Unit> {
        val store = store()
        val p = store.publish(MarketFixtures.publishSkill())
        val v2 = PublishVersionRequest(changelog = "shorter", skill = MarketFixtures.skill().copy(name = "Water coach 2"))

        assertIs<EditResult.Forbidden>(store.addVersion(p.listingId, "wrong", v2))
        assertIs<EditResult.NotFound>(store.addVersion("lst_nope", p.editKey, v2))
        val ok = assertIs<EditResult.Ok>(store.addVersion(p.listingId, p.editKey, v2))
        assertEquals(2, ok.detail.version.version)
        assertEquals(2, store.get(p.listingId)?.listing?.latestVersion)
        assertEquals("Water coach", store.version(p.listingId, 1)?.skill?.name)
        assertEquals("Water coach 2", store.version(p.listingId, 2)?.skill?.name)
        assertNull(store.version(p.listingId, 3))
    }

    @Test
    fun `search filters by kind, text and tag, sorts and pages`() = runBlocking<Unit> {
        val store = store()
        val water = store.publish(MarketFixtures.publishSkill(title = "Water coach", tags = listOf("health")))
        store.publish(MarketFixtures.publishSkill(title = "Budget helper", tags = listOf("money")).copy(summary = "Tracks spending"))
        val buddy = store.publish(MarketFixtures.publishAgent(title = "Hydration buddy"))
        store.install(buddy.listingId, "device-aaaaaaaa")

        assertEquals(3, store.search(null, null, null, ListingSort.NEW, 1, 20).total)
        assertEquals(listOf("Hydration buddy"), store.search(ListingKind.AGENT, null, null, ListingSort.NEW, 1, 20).items.map { it.title })
        assertEquals(listOf("Water coach"), store.search(null, "WATER", null, ListingSort.NEW, 1, 20).items.map { it.title })
        assertEquals(setOf(water.listingId, buddy.listingId), store.search(null, null, "health", ListingSort.NEW, 1, 20).items.map { it.listingId }.toSet())
        assertEquals("Hydration buddy", store.search(null, null, null, ListingSort.POPULAR, 1, 20).items.first().title)
        assertEquals("Hydration buddy", store.search(null, null, null, ListingSort.NEW, 1, 20).items.first().title, "newest first")

        val page2 = store.search(null, null, null, ListingSort.NEW, 2, 2)
        assertEquals(3, page2.total)
        assertEquals(1, page2.items.size)
    }

    @Test
    fun `an unlisted listing is reachable by link but not in search`() = runBlocking<Unit> {
        val store = store()
        val p = store.publish(MarketFixtures.publishSkill())
        assertIs<EditResult.Ok>(store.update(p.listingId, p.editKey, UpdateListingRequest(status = ListingStatus.UNLISTED, title = "Renamed")))
        assertEquals("Renamed", store.get(p.listingId)?.listing?.title)
        assertEquals(0, store.search(null, null, null, ListingSort.NEW, 1, 20).total)
    }

    @Test
    fun `installs count once per device`() = runBlocking<Unit> {
        val store = store()
        val p = store.publish(MarketFixtures.publishSkill())
        assertTrue(store.install(p.listingId, "device-aaaaaaaa"))
        store.install(p.listingId, "device-aaaaaaaa")
        store.install(p.listingId, "device-bbbbbbbb")
        assertFalse(store.install("lst_nope", "device-aaaaaaaa"))
        assertEquals(2, store.get(p.listingId)?.listing?.installs)
    }

    @Test
    fun `three distinct reporters hide a listing for everyone`() = runBlocking<Unit> {
        val store = MarketStore(newDir(), now = { clock++ }, hideAfterReports = 3)
        val p = store.publish(MarketFixtures.publishSkill())
        store.report(p.listingId, "device-aaaaaaaa", "ip-1", ReportReason.SPAM)
        store.report(p.listingId, "device-aaaaaaaa", "ip-2", ReportReason.SPAM)
        store.report(p.listingId, "device-bbbbbbbb", "ip-2", ReportReason.HARMFUL)
        assertNotNull(store.get(p.listingId), "two distinct reporters are not enough")

        store.report(p.listingId, "device-cccccccc", "ip-3", ReportReason.PROMPT_INJECTION)
        assertNull(store.get(p.listingId))
        assertNull(store.version(p.listingId, 1))
        assertEquals(0, store.search(null, null, null, ListingSort.NEW, 1, 20).total)
        assertIs<EditResult.NotFound>(store.update(p.listingId, p.editKey, UpdateListingRequest(status = ListingStatus.LISTED)))
    }

    @Test
    fun `one network inventing device ids cannot hide a listing alone`() = runBlocking<Unit> {
        val store = MarketStore(newDir(), now = { clock++ }, hideAfterReports = 3)
        val p = store.publish(MarketFixtures.publishSkill())
        repeat(10) { store.report(p.listingId, "device-fake000$it", "same-ip", ReportReason.SPAM) }
        assertNotNull(store.get(p.listingId))
    }
}
