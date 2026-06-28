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

data class ScheduledWorksUiState(
  val works: List<ScheduledWorkItem> = emptyList(),
  val isLoading: Boolean = true,
)

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(ScheduledWorksViewModel::class)
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
        .map { infos ->
          infos.map { info ->
            val tags = info.tags
            val title = tags.find { it.startsWith("title:") }?.removePrefix("title:") ?: ""
            val message = tags.find { it.startsWith("msg:") }?.removePrefix("msg:") ?: ""
            val type = when {
              "periodic" in tags -> "periodic"
              "one_time" in tags -> "one_time"
              else -> "unknown"
            }
            // uniqueWorkName хранится в теге "id:<uuid>"; WorkManager также добавляет
            // авто-тег с именем класса воркера, поэтому ищем по явному префиксу.
            val uniqueWorkName = tags.find { it.startsWith("id:") }?.removePrefix("id:")
              ?: info.id.toString()

            ScheduledWorkItem(
              id = uniqueWorkName,
              title = title,
              message = message,
              state = info.state.name,
              type = type,
              nextScheduleTimeMillis = info.nextScheduleTimeMillis,
            )
          }
        }
        .collect { works ->
          _state.value = ScheduledWorksUiState(
            works = works,
            isLoading = false,
          )
        }
    }
  }

  fun cancelWork(id: String) {
    workManager.cancelUniqueWork(id)
  }
}
