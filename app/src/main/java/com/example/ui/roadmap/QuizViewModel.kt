package com.aiopenpocket.ui.roadmap

// @trace ai-academy-onboarding-5d51bb

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiopenpocket.agent.core.identity.UserPreferencesRepository
import com.aiopenpocket.di.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metro.binding
import kotlinx.coroutines.launch

@Inject
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
@ViewModelKey(QuizViewModel::class)
class QuizViewModel(
  private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

  /** Marks the lesson complete (whether the quiz was passed or skipped), then invokes [onDone]. */
  fun finish(levelId: String, onDone: () -> Unit) {
    viewModelScope.launch {
      userPreferencesRepository.completeRoadmapLevel(levelId)
      onDone()
    }
  }
}
