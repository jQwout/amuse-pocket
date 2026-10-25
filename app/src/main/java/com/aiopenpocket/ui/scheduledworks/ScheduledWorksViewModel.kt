package com.aiopenpocket.ui.scheduledworks

// @trace background-tasks-15a34a

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.lifecycle.asFlow
import java.util.UUID

data class ScheduledWorksUiState(
  val works: List<ScheduledWorkItem> = emptyList(),
  val isLoading: Boolean = true,
)

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey
class ScheduledWorksViewModel(
  context: Context,
) : ViewModel() {

  private val workManager = WorkManager.getInstance(context)

  private val _state = MutableStateFlow(ScheduledWorksUiState())
  val state: StateFlow<ScheduledWorksUiState> = _state.asStateFlow()

  init {
    viewModelScope.launch {
      val query = WorkQuery.fromStates(
        listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
      )
      workManager.getWorkInfosLiveData(query)
        .asFlow()
        // Только задачи тула work_manager — чужие (напоминания, служебные воркеры) отсекает маппер.
        .map { infos -> infos.mapNotNull { it.toScheduledWorkItem() } }
        .collect { works ->
          _state.value = ScheduledWorksUiState(
            works = works,
            isLoading = false,
          )
        }
    }
  }

  /**
   * Тул ставит задачи как unique work, поэтому отменяем по уникальному имени из тега `id:`.
   * Без тега cancelUniqueWork(uuid) не совпал бы ни с чем — тогда отменяем по самому UUID.
   */
  fun cancelWork(work: ScheduledWorkItem) {
    val uniqueName = work.uniqueName
    if (uniqueName != null) {
      workManager.cancelUniqueWork(uniqueName)
    } else {
      workManager.cancelWorkById(UUID.fromString(work.id))
    }
  }
}
