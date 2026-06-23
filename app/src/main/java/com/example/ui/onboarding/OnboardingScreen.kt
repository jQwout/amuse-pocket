package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.lesson.LessonStep
import com.aiopenpocket.ui.onboarding.components.LanguageSelectionStep
import com.aiopenpocket.ui.onboarding.components.PersonaSelectionStep
import com.aiopenpocket.ui.onboarding.components.ProfileForm
import com.aiopenpocket.ui.roadmap.QuizQuestion
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

    // В режиме правки подтягиваем сохранённые значения, иначе завершение затрёт профиль пустыми полями.
    LaunchedEffect(editMode) {
        if (editMode) viewModel.loadExistingProfile()
    }

    // В режиме правки флаг уже выставлен, поэтому закрываемся сами, как только сохранили.
    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted && editMode) onClose?.invoke()
    }

    val step = state.currentStep
    if (step == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    // Персона — самодостаточный полноэкранный шаг со своим Scaffold.
    if (step.type == "persona") {
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

    val progress = (state.currentStepIndex + 1) / state.steps.size.toFloat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (editMode) "Настройки урока 1" else "Урок 1 · Анатомия чата") },
                navigationIcon = {
                    if (editMode || state.currentStepIndex > 0) {
                        IconButton(onClick = {
                            if (state.currentStepIndex > 0) viewModel.handleIntent(OnboardingIntent.PreviousStep)
                            else onClose?.invoke()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
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
                if (step.type == "text") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { viewModel.handleIntent(OnboardingIntent.SkipStep) }) {
                            Text("Пропустить")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(
                                onClick = { viewModel.handleIntent(OnboardingIntent.OpenAsk) },
                            ) {
                                Text("Спросить")
                            }
                            Spacer(Modifier.size(8.dp))
                            Button(
                                onClick = { viewModel.handleIntent(OnboardingIntent.NextStep) },
                                modifier = Modifier.testTag("onboarding_next"),
                            ) {
                                Text(state.primaryButtonLabel)
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f))
                        Button(
                            onClick = { viewModel.handleIntent(OnboardingIntent.NextStep) },
                            enabled = state.canProceed,
                            modifier = Modifier.testTag("onboarding_next"),
                        ) {
                            if (state.isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                      .size(16.dp)
                                      .padding(end = 4.dp),
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
            when (step.type) {
                "language" -> LanguageSelectionStep(
                    selectedLanguage = state.selectedLanguage,
                    onSelectLanguage = { viewModel.handleIntent(OnboardingIntent.SelectLanguage(it)) },
                    modifier = Modifier.weight(1f),
                )

                "profile" -> ProfileStepContent(state, viewModel, step, Modifier.weight(1f))
                "quiz" -> QuizStepContent(step, Modifier.weight(1f))
                "text" -> TextStepContent(step, Modifier.weight(1f))
                else -> Spacer(Modifier.weight(1f))
            }

            if (state.askSheetOpen && step.type == "text") {
                AskSheet(
                    stepText = step.text,
                    thread = state.askThread,
                    isAsking = state.isAsking,
                    error = state.askError,
                    onSend = { viewModel.handleIntent(OnboardingIntent.SendAsk(it)) },
                    onCancel = { viewModel.handleIntent(OnboardingIntent.CancelAsk) },
                    onDismiss = { viewModel.handleIntent(OnboardingIntent.CloseAsk) },
                )
            }
        }
    }
}

@Composable
private fun TextStepContent(
    step: LessonStep,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            text = step.text,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskSheet(
    stepText: String,
    thread: List<AskMessage>,
    isAsking: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .imePadding(),
        ) {
            Text("Спросить про этот шаг", style = MaterialTheme.typography.titleMedium)
            Text(
                text = stepText.take(48).let { if (stepText.length > 48) "$it…" else it },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                thread.forEach { msg -> AskBubble(msg, isAsking) }
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            var input by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Спроси своими словами…") },
                    maxLines = 4,
                )
                Spacer(Modifier.size(8.dp))
                if (isAsking) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Остановить")
                    }
                } else {
                    IconButton(
                        onClick = {
                            if (input.isNotBlank()) {
                                onSend(input)
                                input = ""
                            }
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
                    }
                }
            }
        }
    }
}

@Composable
private fun AskBubble(msg: AskMessage, isAsking: Boolean) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (msg.content.isEmpty() && isAsking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(text = msg.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun ProfileStepContent(
    state: OnboardingUiState,
    viewModel: OnboardingViewModel,
    step: LessonStep,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = "Расскажи о себе",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(16.dp),
        )
        Text(
            text = step.note.ifBlank { "Агент будет использовать это в диалогах" },
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
            onPreferredAddressChange = { viewModel.handleIntent(OnboardingIntent.UpdatePreferredAddress(it)) },
            onExtraInfoChange = { viewModel.handleIntent(OnboardingIntent.UpdateExtraInfo(it)) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuizStepContent(
    step: LessonStep,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp),
    ) {
        Text(
            text = "Закрепим пройденное",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        QuizContent(
            questions = step.questions.map {
                QuizQuestion(
                    question = it.q,
                    options = it.options,
                    correctIndex = it.correct,
                    explanation = it.explain,
                )
            },
        )
        Spacer(Modifier.size(16.dp))
    }
}
