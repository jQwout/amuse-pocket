package com.aiopenpocket.ui.agents

// @trace user-agent-builder-848153

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.data.database.AppDatabase
import com.aiopenpocket.data.repository.AgentFilesRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentInspectorViewModelTest {

  private lateinit var db: AppDatabase
  private lateinit var heartbeatDao: HeartbeatDao
  private lateinit var agentFilesRepository: AgentFilesRepository
  private lateinit var vm: AgentInspectorViewModel

  // viewModelScope = Dispatchers.Main.immediate; под Robolectric main looper по умолчанию PAUSED
  // (задачи копятся в очереди, а не бегут сразу), из-за чего stateIn/WhileSubscribed зависает без
  // подкачки. Подменяем Main на UnconfinedTestDispatcher — как рекомендует kotlinx-coroutines-test
  // для ViewModel-тестов, — и код исполняется сразу же, без ручных idle().
  private val mainDispatcher = UnconfinedTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(mainDispatcher)
    val context = ApplicationProvider.getApplicationContext<Context>()
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
      .allowMainThreadQueries()
      .build()
    heartbeatDao = db.heartbeatDao()
    agentFilesRepository = AgentFilesRepository(RuntimeEnvironment.getApplication())
    vm = AgentInspectorViewModel(heartbeatDao, agentFilesRepository)
  }

  @After
  fun tearDown() {
    db.close()
    Dispatchers.resetMain()
  }

  private fun hb(id: String, agentId: String?, status: String = "scheduled") = HeartbeatEntity(
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

  private fun agentSandboxDir(agentId: String): File =
    File(File(RuntimeEnvironment.getApplication().filesDir, "agents"), agentId).apply { mkdirs() }

  @Test
  fun `select loads active heartbeats and this agent's own sandbox files`() = runTest {
    heartbeatDao.insert(hb(id = "b1", agentId = "budget"))
    heartbeatDao.insert(hb(id = "b2", agentId = "budget", status = "cancelled"))
    heartbeatDao.insert(hb(id = "d1", agentId = "diary"))

    File(agentSandboxDir("budget"), "report.txt").writeText("data")
    // Task 6: diary's own sandbox file must NOT leak into budget's inspector state.
    File(agentSandboxDir("diary"), "diary-secret.txt").writeText("private")

    vm.select("budget")

    val state = vm.state.first { it.activeHeartbeats.isNotEmpty() && it.files.isNotEmpty() }

    assertEquals(listOf("b1"), state.activeHeartbeats.map { it.id })
    assertEquals(listOf("report.txt"), state.files.map { it.name })
  }

  @Test
  fun `switching agent via select re-subscribes to the new agent's heartbeats`() = runTest {
    heartbeatDao.insert(hb(id = "b1", agentId = "budget"))
    heartbeatDao.insert(hb(id = "d1", agentId = "diary"))

    vm.select("budget")
    vm.state.first { it.activeHeartbeats.any { hb -> hb.id == "b1" } }

    vm.select("diary")
    val state = vm.state.first { it.activeHeartbeats.any { hb -> hb.id == "d1" } }

    assertEquals(listOf("d1"), state.activeHeartbeats.map { it.id })
  }
}
