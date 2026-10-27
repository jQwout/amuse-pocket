package com.aiopenpocket.agent.tools

// @trace user-agent-builder-848153

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.repository.MemoryRepository
import com.aiopenpocket.agent.loop.TurnContextHolder
import com.aiopenpocket.data.database.AppDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `returnsUntrustedContent` decides whether a tool's output goes into the `<tool_output trust="untrusted">`
 * envelope ([com.aiopenpocket.agent.loop.ReactLoopRunner.toolMessageContent]) — that is, whether the model
 * reads instructions embedded there as data or as commands.
 *
 * `memory_tool` did not set this flag, and that opened a bypass of the whole mechanism: FileTools/
 * CalendarTool honestly mark their output as untrusted, but the model can read such text and put it into
 * `remember_fact` — after which `search_memory`/`recall_episodes` return exactly the same external text
 * WITHOUT the envelope. Memory became a laundering channel around the fence.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ToolUntrustedContentTest {

  private lateinit var db: AppDatabase
  private lateinit var memoryTools: MemoryTools
  private lateinit var fileTools: FileTools
  private lateinit var databaseQueryTool: DatabaseQueryTool

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val application = ApplicationProvider.getApplicationContext<Application>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    val holder = TurnContextHolder()
    memoryTools = MemoryTools(MemoryRepository(db.factDao(), db.episodeDao()), holder)
    fileTools = FileTools(application, holder)
    databaseQueryTool = DatabaseQueryTool(db)
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun `memory_tool returns untrusted content - stored facts can carry laundered external text`() {
    assertTrue(memoryTools.returnsUntrustedContent)
  }

  @Test
  fun `the tools that read outside the app stay marked untrusted`() {
    // A regression guard for the already marked ones: removing the flag here silently removes the fence in ReactLoopRunner.
    assertTrue("file_tool", fileTools.returnsUntrustedContent)
    assertTrue("database_query", databaseQueryTool.returnsUntrustedContent)
  }
}
