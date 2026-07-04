package com.aiopenpocket.agent.core.srs

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real Room-generated schema/queries for the new srs_words table (version 9). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SrsWordDaoTest {

  private lateinit var db: AppDatabase
  private lateinit var dao: SrsWordDao

  @Before
  fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(),
      AppDatabase::class.java,
    ).allowMainThreadQueries().build()
    dao = db.srsWordDao()
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun word(id: String, lang: String, term: String, stage: Int = 0, due: Long = 0L) =
    SrsWordEntity(id, lang, term, "перевод", "", stage, due, 1L, 1L)

  @Test
  fun upsertReplacesByPrimaryKey() = runBlocking {
    dao.upsert(word("en:house", "en", "house", stage = 0))
    dao.upsert(word("en:house", "en", "house", stage = 3))
    val all = dao.getByLang("en")
    assertEquals(1, all.size)
    assertEquals(3, all.first().stage)
  }

  @Test
  fun getByLangFiltersByLanguage() = runBlocking {
    dao.upsert(word("en:house", "en", "house"))
    dao.upsert(word("de:haus", "de", "Haus"))
    assertEquals(listOf("house"), dao.getByLang("en").map { it.term })
    assertEquals(2, dao.getAll().size)
  }

  @Test
  fun getByIdAndDelete() = runBlocking {
    dao.upsert(word("en:cat", "en", "cat"))
    assertNotNull(dao.getById("en:cat"))
    dao.delete("en:cat")
    assertNull(dao.getById("en:cat"))
  }
}
