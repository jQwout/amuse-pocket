package com.aiopenpocket.ui.agents

// @trace agent-inspector-6e41eb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.memory.dao.HeartbeatDao
import com.aiopenpocket.agent.core.memory.entity.HeartbeatEntity
import com.aiopenpocket.data.repository.AgentFile
import com.aiopenpocket.data.repository.AgentFilesRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

data class AgentInspectorState(
  val activeHeartbeats: List<HeartbeatEntity> = emptyList(),
  val files: List<AgentFile> = emptyList(),
)

/**
 * Инспектор агента: живой снимок его активных heartbeat'ов (Task 1) и файлов общей песочницы
 * (Task 2). agentId приходит ПОСЛЕ конструктора через [select] — тот же паттерн, что convIdFlow
 * в ChatViewModel (ViewModel не берёт runtime-аргументы через metroViewModel()).
 */
@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(AgentInspectorViewModel::class)
class AgentInspectorViewModel(
  private val heartbeatDao: HeartbeatDao,
  private val agentFilesRepository: AgentFilesRepository,
) : ViewModel() {

  private val agentIdFlow = MutableStateFlow<String?>(null)

  @OptIn(ExperimentalCoroutinesApi::class)
  private val filesFlow: Flow<List<AgentFile>> = agentIdFlow
    .filterNotNull()
    .mapLatest { withContext(Dispatchers.IO) { agentFilesRepository.list() } }

  @OptIn(ExperimentalCoroutinesApi::class)
  private val heartbeatsFlow: Flow<List<HeartbeatEntity>> = agentIdFlow
    .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else heartbeatDao.observeActiveByAgent(id) }

  val state: StateFlow<AgentInspectorState> =
    combine(heartbeatsFlow, filesFlow.onStart { emit(emptyList()) }) { hb, files ->
      AgentInspectorState(activeHeartbeats = hb, files = files)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AgentInspectorState())

  /** Вызывается при открытии листа инспектора для агента; переустанавливает подписки на его данные. */
  fun select(agentId: String) {
    agentIdFlow.value = agentId
  }
}
