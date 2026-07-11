package com.aiopenpocket.agent.core.memory.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeartbeatDaoTest {

  private lateinit var db: AppDatabase
  private lateinit var dao: HeartbeatDao

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    dao = db.heartbeatDao()
  }

  @After
  fun tearDown() = db.close()

  private fun hb(
    id: String = "hb1",
    status: String = "scheduled",
    agentId: String? = null,
  ) = HeartbeatEntity(
    id = id,
    title = "Проверка бюджета",
    mode = "agent",
    prompt = "проверь бюджет",
    agentId = agentId,
    conversationId = "conv1",
    kind = "periodic",
    intervalMinutes = 30,
    status = status,
  )

  @Test
  fun `insert and getById round-trip`() = runTest {
    dao.insert(hb())
    val loaded = dao.getById("hb1")
    assertEquals("Проверка бюджета", loaded?.title)
    assertEquals("agent", loaded?.mode)
    assertEquals(30L, loaded?.intervalMinutes)
  }

  @Test
  fun `observe emits updates`() = runTest {
    dao.insert(hb())
    dao.setStatus("hb1", "cancelled")
    assertEquals("cancelled", dao.observe("hb1").first()?.status)
  }

  @Test
  fun `listActive returns only scheduled`() = runTest {
    dao.insert(hb(id = "a"))
    dao.insert(hb(id = "b", status = "cancelled"))
    assertEquals(listOf("a"), dao.listActive().map { it.id })
  }

  @Test
  fun `recordRun updates status lastRunAt lastResult`() = runTest {
    dao.insert(hb())
    dao.recordRun("hb1", status = "fired", lastRunAt = 42L, lastResult = "ok")
    val loaded = dao.getById("hb1")
    assertEquals("fired", loaded?.status)
    assertEquals(42L, loaded?.lastRunAt)
    assertEquals("ok", loaded?.lastResult)
  }

  @Test
  fun `getById returns null for unknown id`() = runTest {
    assertNull(dao.getById("nope"))
  }

  @Test
  fun `observeActiveByAgent returns only scheduled heartbeats for given agent`() = runTest {
    dao.insert(hb(id = "b1", agentId = "budget"))
    dao.insert(hb(id = "b2", agentId = "budget"))
    dao.insert(hb(id = "d1", agentId = "diary"))
    dao.insert(hb(id = "n1", agentId = null))

    val active = dao.observeActiveByAgent("budget").first()

    assertEquals(2, active.size)
    assertEquals(true, active.all { it.agentId == "budget" })

    dao.insert(hb(id = "b3", agentId = "budget", status = "cancelled"))
    val stillActive = dao.observeActiveByAgent("budget").first()

    assertEquals(2, stillActive.size)
  }
}
