package com.aiopenpocket.agent.core.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.model.LinkDirection
import com.aiopenpocket.agent.core.memory.repository.WikiRepository
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WikiRepositoryTest {

  private lateinit var db: AppDatabase
  private lateinit var repo: WikiRepository

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    repo = WikiRepository(db.wikiPageDao(), db.wikiTagDao(), db.wikiLinkDao())
  }

  @After
  fun tearDown() {
    db.close()
  }

  @Test
  fun `upsert derives slug from title and stores tags`() = runBlocking {
    val page = repo.upsertPage(slug = null, title = "Моя работа", content = "Backend dev", tags = listOf("work", "career"))
    assertEquals("моя-работа", page.slug)
    assertEquals(listOf("career", "work"), page.tags.sorted())
  }

  @Test
  fun `upsert on same slug updates in place and keeps id and createdAt`() = runBlocking {
    val first = repo.upsertPage(slug = "city", title = "Город", content = "Amsterdam", tags = listOf("place"))
    val second = repo.upsertPage(slug = "city", title = "Город", content = "Rotterdam", tags = listOf("place", "moved"))

    assertEquals(first.id, second.id)
    assertEquals(first.createdAt, second.createdAt)
    val view = repo.getPage("city")
    assertEquals("Rotterdam", view?.page?.content)
    assertEquals(listOf("moved", "place"), view?.page?.tags?.sorted())
  }

  @Test
  fun `null tags leave existing tags untouched`() = runBlocking {
    repo.upsertPage(slug = "p", title = "P", content = "v1", tags = listOf("a", "b"))
    repo.upsertPage(slug = "p", title = "P", content = "v2", tags = null)
    assertEquals(listOf("a", "b"), repo.getPage("p")?.page?.tags?.sorted())
  }

  @Test
  fun `search by tag ranks by overlap`() = runBlocking {
    repo.upsertPage(slug = "a", title = "A", content = "", tags = listOf("x", "y"))
    repo.upsertPage(slug = "b", title = "B", content = "", tags = listOf("x"))

    val results = repo.search(tags = listOf("x", "y"))
    assertEquals(listOf("a", "b"), results.map { it.slug })
  }

  @Test
  fun `search by text finds content match`() = runBlocking {
    repo.upsertPage(slug = "note", title = "Note", content = "remember the milk", tags = emptyList())
    val results = repo.search(query = "milk")
    assertEquals(listOf("note"), results.map { it.slug })
  }

  @Test
  fun `link is traversable in both directions`() = runBlocking {
    repo.upsertPage(slug = "work", title = "Work", content = "", tags = emptyList())
    repo.upsertPage(slug = "city", title = "City", content = "", tags = emptyList())

    assertTrue(repo.link("work", "city", "located_in"))

    val workNeighbors = repo.getPage("work")?.neighbors.orEmpty()
    assertEquals(1, workNeighbors.size)
    assertEquals(LinkDirection.OUTGOING, workNeighbors.first().direction)
    assertEquals("city", workNeighbors.first().slug)

    val cityNeighbors = repo.getPage("city")?.neighbors.orEmpty()
    assertEquals(1, cityNeighbors.size)
    assertEquals(LinkDirection.INCOMING, cityNeighbors.first().direction)
    assertEquals("work", cityNeighbors.first().slug)
  }

  @Test
  fun `link to missing page fails`() = runBlocking {
    repo.upsertPage(slug = "work", title = "Work", content = "", tags = emptyList())
    assertFalse(repo.link("work", "nope"))
  }

  @Test
  fun `deleting a page cascades tags and links`() = runBlocking {
    repo.upsertPage(slug = "a", title = "A", content = "", tags = listOf("t"))
    repo.upsertPage(slug = "b", title = "B", content = "", tags = emptyList())
    repo.link("a", "b")

    assertTrue(repo.deletePage("a"))

    assertNull(repo.getPage("a"))
    // b survives, and its inbound link from a is gone.
    assertNotNull(repo.getPage("b"))
    assertTrue(repo.getPage("b")?.neighbors.orEmpty().isEmpty())
  }
}
