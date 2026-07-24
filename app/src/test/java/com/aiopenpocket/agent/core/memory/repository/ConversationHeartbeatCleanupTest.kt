package com.aiopenpocket.agent.core.memory.repository

// @trace heartbeat-napominalki-agenta-5f0442

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.agent.core.plan.schedule.HeartbeatScheduler
import com.aiopenpocket.data.database.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [ConversationHeartbeatCleanup] over a real (in-memory) Room DB + a fake [HeartbeatScheduler]: proves
 * deleting a conversation tears down its heartbeats' WorkManager jobs AND rows — the fix for periodic
 * heartbeats surviving a conversation delete (no FK cascade reaches `heartbeats`) and firing forever.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationHeartbeatCleanupTest {

  private lateinit var db: AppDatabase
  private lateinit var scheduler: RecordingScheduler
  private lateinit var cleanup: ConversationHeartbeatCleanup

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    scheduler = RecordingScheduler()
    cleanup = ConversationHeartbeatCleanup(db.heartbeatDao(), scheduler)
  }

  @After
  fun tearDown() = db.close()

  private class RecordingScheduler : HeartbeatScheduler {
    val cancelled = mutableListOf<String>()
    override suspend fun schedule(entity: HeartbeatEntity) {}
    override suspend fun cancel(heartbeatId: String) { cancelled += heartbeatId }
  }

  private fun hb(id: String, conversationId: String) =
    HeartbeatEntity(id = id, title = "t", mode = "agent", conversationId = conversationId, kind = "periodic", intervalMinutes = 60)

  @Test
  fun `cancelForConversation cancels the WM job and marks the row for that conversation's heartbeats only`() = runTest {
    db.heartbeatDao().insert(hb("hb-c1", "c1"))
    db.heartbeatDao().insert(hb("hb-c2", "c2"))

    cleanup.cancelForConversation("c1")

    assertEquals("only c1's heartbeat WM job is cancelled", listOf("hb-c1"), scheduler.cancelled)
    assertEquals("c1's row is marked cancelled", "cancelled", db.heartbeatDao().getById("hb-c1")?.status)
    assertEquals("c2's heartbeat is untouched", "scheduled", db.heartbeatDao().getById("hb-c2")?.status)
  }
}
