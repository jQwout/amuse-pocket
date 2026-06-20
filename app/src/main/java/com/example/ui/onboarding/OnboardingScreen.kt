package com.aiopenpocket.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.aiopenpocket.ui.onboarding.components.LanguageSelectionStep
import com.aiopenpocket.ui.onboarding.components.PersonaSelectionStep
import com.aiopenpocket.ui.onboarding.components.ProfileForm
import com.aiopenpocket.ui.onboarding.components.WrapupStep
import com.aiopenpocket.ui.roadmap.LESSON_QUIZZES
import com.aiopenpocket.ui.roadmap.components.QuizContent
import dev.zacsweers.metrox.viewmodel.metroViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
  // Non-null when reached from the roadmap as a re-edit; closes back to the previous screen.
  onClose: (() -> Unit)? = null,
) {
  val viewModel: OnboardingViewModel = metroViewModel()
  val state by viewModel.state.collectAsState()
  val editMode = onClose != null

  // В режиме правки подтягиваем сохранённые значения, иначе Complete затрёт профиль пустыми полями.
  LaunchedEffect(editMode) {
    if (editMode) viewModel.loadExistingProfile()
  }

  LaunchedEffect(state.currentStep) {
    if (state.currentStep == OnboardingStep.INTRO) {
      viewModel.handleIntent(OnboardingIntent.StartIntroStreaming)
    }
  }

  // In edit mode the flag is already set, so navigate back ourselves once saved.
  LaunchedEffect(state.isCompleted) {
    if (state.isCompleted && editMode) onClose?.invoke()
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
    OnboardingStep.LANGUAGE -> 0
    OnboardingStep.INTRO -> 1
    OnboardingStep.PERSONA -> 2
    OnboardingStep.PROFILE -> 3
    OnboardingStep.WRAPUP -> 4
    OnboardingStep.QUIZ -> 5
  }
  val progress = (stepIndex + 1) / 6f

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(if (editMode) "Настройки урока 1" else "Настройка") },
        navigationIcon = {
          if (editMode) {
            IconButton(onClick = { onClose?.invoke() }) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Закрыть")
            }
          }
        },
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
          if (state.currentStep != OnboardingStep.LANGUAGE) {
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
              onClick = { viewModel.handleIntent(OnboardingIntent.NextStep) },
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
        OnboardingStep.LANGUAGE -> {
          LanguageSelectionStep(
            selectedLanguage = state.selectedLanguage,
            onSelectLanguage = { viewModel.handleIntent(OnboardingIntent.SelectLanguage(it)) },
            modifier = Modifier.weight(1f),
          )
        }
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
              religion = state.religion,
              preferredAddress = state.preferredAddress,
              extraInfo = state.extraInfo,
              onNameChange = { viewModel.handleIntent(OnboardingIntent.UpdateName(it)) },
              onAgeChange = { viewModel.handleIntent(OnboardingIntent.UpdateAge(it)) },
              onGenderChange = { viewModel.handleIntent(OnboardingIntent.UpdateGender(it)) },
              onReligionChange = { viewModel.handleIntent(OnboardingIntent.UpdateReligion(it)) },
              onPreferredAddressChange = {
                viewModel.handleIntent(OnboardingIntent.UpdatePreferredAddress(it))
              },
              onExtraInfoChange = { viewModel.handleIntent(OnboardingIntent.UpdateExtraInfo(it)) },
              modifier = Modifier.weight(1f),
            )
          }
        }
        OnboardingStep.WRAPUP -> {
          WrapupStep(
            personaId = state.selectedPersonaId,
            name = state.name,
            preferredAddress = state.preferredAddress,
            modifier = Modifier.weight(1f),
          )
        }
        OnboardingStep.QUIZ -> {
          Column(
            modifier = Modifier
              .weight(1f)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 24.dp),
          ) {
            Text(
              text = "Закрепим пройденное",
              style = MaterialTheme.typography.headlineSmall,
              modifier = Modifier.padding(vertical = 16.dp),
            )
            QuizContent(questions = LESSON_QUIZZES["l1_welcome"].orEmpty())
            TextButton(
              onClick = { viewModel.handleIntent(OnboardingIntent.NextStep) },
              modifier = Modifier.padding(top = 8.dp),
            ) {
              Text("Пропустить проверку")
            }
          }
        }
      }
    }
  }
}
