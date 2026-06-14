package com.aiopenpocket.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.onboarding.components.IntroStreamingStep
import com.aiopenpocket.ui.onboarding.components.PersonaSelectionStep
import com.aiopenpocket.ui.onboarding.components.ProfileForm
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen() {
  val viewModel: OnboardingViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()

  LaunchedEffect(state.currentStep) {
    if (state.currentStep == OnboardingStep.INTRO) {
      viewModel.handleIntent(OnboardingIntent.StartIntroStreaming)
    }
  }

  if (state.currentStep == OnboardingStep.PERSONA) {
    PersonaSelectionStep(
      selectedPersonaId = state.selectedPersonaId,
      isSaving = state.isSaving,
      error = state.error,
      onSelectPersona = { viewModel.handleIntent(OnboardingIntent.SelectPersona(it)) },
      onBack = { viewModel.handleIntent(OnboardingIntent.PreviousStep) },
      onApply = { viewModel.handleIntent(OnboardingIntent.NextStep) },
    )
    return
  }

  val stepIndex = when (state.currentStep) {
    OnboardingStep.INTRO -> 0
    OnboardingStep.PERSONA -> 1
    OnboardingStep.PROFILE -> 2
  }
  val progress = (stepIndex + 1) / 3f

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("Настройка") },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = MaterialTheme.colorScheme.surface,
        ),
      )
    },
    bottomBar = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
      ) {
        if (state.error != null) {
          Text(
            text = state.error!!,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (state.currentStep != OnboardingStep.INTRO) {
            TextButton(
              onClick = { viewModel.handleIntent(OnboardingIntent.PreviousStep) },
              modifier = Modifier.testTag("onboarding_back"),
            ) {
              Text("Назад")
            }
          }
          Box(modifier = Modifier.weight(1f))
          AnimatedVisibility(
            visible = state.canProceed || state.currentStep != OnboardingStep.INTRO,
            enter = fadeIn(),
          ) {
            Button(
              onClick = {
                if (state.currentStep == OnboardingStep.PROFILE) {
                  viewModel.handleIntent(OnboardingIntent.Complete)
                } else {
                  viewModel.handleIntent(OnboardingIntent.NextStep)
                }
              },
              enabled = state.canProceed,
              modifier = Modifier.testTag("onboarding_next"),
            ) {
              if (state.isSaving) {
                CircularProgressIndicator(
                  modifier = Modifier.padding(end = 8.dp),
                  strokeWidth = 2.dp,
                )
              }
              Text(state.primaryButtonLabel)
            }
          }
        }
      }
    },
  ) { padding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(padding),
    ) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
      )
      when (state.currentStep) {
        OnboardingStep.INTRO -> {
          IntroStreamingStep(
            streamingText = state.introStreamingText,
            isStreaming = state.isIntroStreaming,
            modifier = Modifier.weight(1f),
          )
        }
        OnboardingStep.PERSONA -> Unit
        OnboardingStep.PROFILE -> {
          Column(modifier = Modifier.fillMaxSize()) {
            Text(
              text = "Расскажи о себе",
              style = MaterialTheme.typography.headlineSmall,
              modifier = Modifier.padding(16.dp),
            )
            Text(
              text = "Агент будет использовать это в диалогах",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ProfileForm(
              name = state.name,
              age = state.age,
              gender = state.gender,
              preferredAddress = state.preferredAddress,
              extraInfo = state.extraInfo,
              onNameChange = { viewModel.handleIntent(OnboardingIntent.UpdateName(it)) },
              onAgeChange = { viewModel.handleIntent(OnboardingIntent.UpdateAge(it)) },
              onGenderChange = { viewModel.handleIntent(OnboardingIntent.UpdateGender(it)) },
              onPreferredAddressChange = {
                viewModel.handleIntent(OnboardingIntent.UpdatePreferredAddress(it))
              },
              onExtraInfoChange = { viewModel.handleIntent(OnboardingIntent.UpdateExtraInfo(it)) },
              modifier = Modifier.weight(1f),
            )
          }
        }
      }
    }
  }
}
