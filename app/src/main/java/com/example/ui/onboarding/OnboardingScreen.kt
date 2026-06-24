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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.onboarding.components.AboutMeStep
import com.aiopenpocket.ui.onboarding.components.IntroStreamingStep
import com.aiopenpocket.ui.onboarding.components.LanguageSelectionStep
import com.aiopenpocket.ui.onboarding.components.PersonaSelectionStep
import com.aiopenpocket.ui.onboarding.components.WrapupStep
import com.aiopenpocket.ui.roadmap.LESSON_QUIZZES
import com.aiopenpocket.ui.roadmap.components.QuizContent
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val ONBOARDING_STEP_ORDER = OnboardingStep.entries.toList()

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
  LaunchedEffect(editMode) { if (editMode) viewModel.loadExistingProfile() }

  LaunchedEffect(state.currentStep) {
    if (state.currentStep == OnboardingStep.INTRO) {
      viewModel.handleIntent(OnboardingIntent.StartIntroStreaming)
    }
  }

  // In edit mode the flag is already set, so navigate back ourselves once saved.
  LaunchedEffect(state.isCompleted) {
    if (state.isCompleted && editMode) onClose?.invoke()
  }

  OnboardingContent(
    state = state,
    editMode = editMode,
    onClose = onClose,
    onIntent = viewModel::handleIntent,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingContent(
  state: OnboardingUiState,
  editMode: Boolean,
  onClose: (() -> Unit)?,
  onIntent: (OnboardingIntent) -> Unit,
) {
  if (state.currentStep == OnboardingStep.PERSONA) {
    PersonaSelectionStep(
      selectedPersonaId = state.selectedPersonaId,
      isSaving = state.isSaving,
      error = state.error,
      onSelectPersona = { onIntent(OnboardingIntent.SelectPersona(it)) },
      onBack = { onIntent(OnboardingIntent.PreviousStep) },
      onApply = { onIntent(OnboardingIntent.NextStep) },
    )
    return
  }

  val stepIndex = ONBOARDING_STEP_ORDER.indexOf(state.currentStep).coerceAtLeast(0)
  val progress = (stepIndex + 1) / ONBOARDING_STEP_ORDER.size.toFloat()

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
    bottomBar = { OnboardingBottomBar(state = state, onIntent = onIntent) },
  ) { padding ->
    Column(modifier = Modifier.fillMaxSize().padding(padding)) {
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier.fillMaxWidth(),
      )
      OnboardingStepBody(
        state = state,
        onIntent = onIntent,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@Composable
private fun OnboardingBottomBar(
  state: OnboardingUiState,
  onIntent: (OnboardingIntent) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
    state.error?.let {
      Text(
        text = it,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(bottom = 8.dp),
      )
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      if (state.currentStep != OnboardingStep.LANGUAGE) {
        TextButton(
          onClick = { onIntent(OnboardingIntent.PreviousStep) },
          modifier = Modifier.testTag("onboarding_back"),
        ) { Text("Назад") }
      }
      Box(modifier = Modifier.weight(1f))
      AnimatedVisibility(
        visible = state.canProceed || state.currentStep != OnboardingStep.INTRO,
        enter = fadeIn(),
      ) {
        Button(
          onClick = { onIntent(OnboardingIntent.NextStep) },
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
}

@Composable
private fun OnboardingStepBody(
  state: OnboardingUiState,
  onIntent: (OnboardingIntent) -> Unit,
  modifier: Modifier,
) {
  when (state.currentStep) {
    OnboardingStep.LANGUAGE -> LanguageSelectionStep(
      selectedLanguage = state.selectedLanguage,
      onSelectLanguage = { onIntent(OnboardingIntent.SelectLanguage(it)) },
      modifier = modifier,
    )
    OnboardingStep.INTRO -> IntroStreamingStep(
      streamingText = state.introStreamingText,
      isStreaming = state.isIntroStreaming,
      modifier = modifier,
    )
    OnboardingStep.PERSONA -> Unit
    OnboardingStep.PROFILE -> AboutMeStep(
      personaId = state.selectedPersonaId,
      text = state.aboutMeText,
      isExtracting = state.isExtracting,
      extractionError = state.extractionError,
      onTextChange = { onIntent(OnboardingIntent.UpdateAboutMe(it)) },
      modifier = modifier,
    )
    OnboardingStep.WRAPUP -> WrapupStep(
      personaId = state.selectedPersonaId,
      name = state.name,
      preferredAddress = state.preferredAddress,
      modifier = modifier,
    )
    OnboardingStep.QUIZ -> Column(
      modifier = modifier
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
        onClick = { onIntent(OnboardingIntent.NextStep) },
        modifier = Modifier.padding(top = 8.dp),
      ) { Text("Пропустить проверку") }
    }
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF1C1B1F)
@Composable
private fun OnboardingScreenPreview() {
  MyApplicationTheme {
    OnboardingContent(
      state = OnboardingUiState(
        currentStep = OnboardingStep.LANGUAGE,
        selectedLanguage = "ru",
      ),
      editMode = false,
      onClose = null,
      onIntent = {},
    )
  }
}
