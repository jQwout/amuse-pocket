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
 * `returnsUntrustedContent` решает, попадёт ли вывод тула в конверт `<tool_output trust="untrusted">`
 * ([com.aiopenpocket.agent.loop.ReactLoopRunner.toolMessageContent]) — то есть прочтёт модель встроенные
 * туда инструкции как данные или как команды.
 *
 * `memory_tool` этот флаг не ставил, и это открывало обход всего механизма: FileTools/
 * CalendarTool честно помечают свой вывод недоверенным, но модель может прочитать такой текст и
 * положить его в `remember_fact` — после чего `search_memory`/`recall_episodes` возвращают ровно тот
 * же внешний текст уже БЕЗ конверта. Память оказывалась каналом отмывки вокруг ограды.
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
    // Регресс-страховка для уже помеченных: снятие флага здесь молча снимает ограду в ReactLoopRunner.
    assertTrue("file_tool", fileTools.returnsUntrustedContent)
    assertTrue("database_query", databaseQueryTool.returnsUntrustedContent)
  }
}
