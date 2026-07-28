package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.ui.agents.builder.components.CapabilitiesStep
import com.aiopenpocket.ui.agents.builder.components.HooksStep
import com.aiopenpocket.ui.agents.builder.components.IdentityStep
import com.aiopenpocket.ui.agents.builder.components.InstructionsStep
import com.aiopenpocket.ui.agents.builder.components.LoopModesStep
import com.aiopenpocket.ui.agents.builder.components.TestAndPublishStep
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.glassSource
import com.aiopenpocket.ui.components.glass.rememberGlassState

/**
 * Stateless six-step wizard screen — a pure projector over [AgentBuilderUiState]: every field comes
 * in as a parameter, every action goes out through a callback. [AgentBuilderRoute] is the only caller
 * that knows about `AgentBuilderViewModel`/`AgentBuilderTestViewModel`; this file never imports either.
 *
 * Reuses the onboarding wizard's own progress pattern (`OnboardingScreen.kt`: aurora background,
 * transparent [Scaffold], [LinearProgressIndicator] over the current step fraction, a [GlassSurface]
 * bottom bar with Back/Next) rather than [com.aiopenpocket.ui.components.AcademyCollapsingScaffold] —
 * that scaffold's collapsing toolbar exists for scrollable list screens (Agents/Roadmap), and doesn't
 * fit a fixed six-step wizard whose steps aren't all scrollable lists themselves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBuilderScreen(
  state: AgentBuilderUiState,
  availableSkillIds: Set<String>,
  testStatus: TurnStatus?,
  testStreamingText: String,
  testToolTrace: List<ToolTrace>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  onBack: () -> Unit,
  onNext: () -> Unit,
  onExit: () -> Unit,
  onSetExecutionMode: (ExecutionMode) -> Unit,
  onSendTestMessage: (String) -> Unit,
  onPublish: () -> Unit,
  onArchive: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (state.isLoading) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      CircularProgressIndicator()
    }
    return
  }

  val glassState = rememberGlassState()
  val stepIndex = BuilderStep.entries.indexOf(state.step).coerceAtLeast(0)
  val progress = (stepIndex + 1) / BuilderStep.entries.size.toFloat()
  val canAdvance = AgentBuilderValidator.canAdvanceFrom(state.step, state.errors)

  Box(modifier = modifier.fillMaxSize().auroraBackground()) {
    Scaffold(
      containerColor = Color.Transparent,
      topBar = {
        TopAppBar(
          // Стабильный заголовок визарда: имя шага уже есть заголовком-H1 в теле каждого шага —
          // дублировать его ещё и в тулбаре («Инструкции» / «Инструкции») незачем.
          title = { Text("Конструктор агента") },
          navigationIcon = {
            IconButton(onClick = onExit) {
              Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
      },
      bottomBar = {
        BuilderBottomBar(
          saveError = state.saveError,
          showNext = state.step != BuilderStep.TEST,
          canAdvance = canAdvance,
          glassState = glassState,
          onBack = onBack,
          onNext = onNext,
        )
      },
    ) { padding ->
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(padding)
          .glassSource(glassState),
      ) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.weight(1f)) {
          BuilderStepContent(
            state = state,
            availableSkillIds = availableSkillIds,
            testStatus = testStatus,
            testStreamingText = testStreamingText,
            testToolTrace = testToolTrace,
            onUpdateDraft = onUpdateDraft,
            onSetExecutionMode = onSetExecutionMode,
            onSendTestMessage = onSendTestMessage,
            onPublish = onPublish,
            onArchive = onArchive,
          )
        }
      }
    }
  }
}

@Composable
private fun BuilderStepContent(
  state: AgentBuilderUiState,
  availableSkillIds: Set<String>,
  testStatus: TurnStatus?,
  testStreamingText: String,
  testToolTrace: List<ToolTrace>,
  onUpdateDraft: ((AgentDraft) -> AgentDraft) -> Unit,
  onSetExecutionMode: (ExecutionMode) -> Unit,
  onSendTestMessage: (String) -> Unit,
  onPublish: () -> Unit,
  onArchive: () -> Unit,
) {
  val stepErrors = AgentBuilderValidator.errorsForStep(state.errors, state.step)
  when (state.step) {
    BuilderStep.IDENTITY -> IdentityStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
    )

    BuilderStep.INSTRUCTIONS -> InstructionsStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
    )

    BuilderStep.CAPABILITIES -> CapabilitiesStep(
      draft = state.draft,
      errors = stepErrors,
      availableSkillIds = availableSkillIds,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
    )

    BuilderStep.LOOP -> LoopModesStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
    )

    BuilderStep.HOOKS -> HooksStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
    )

    BuilderStep.TEST -> TestAndPublishStep(
      draft = state.draft,
      executionMode = state.executionMode,
      canPublish = state.canPublish,
      // Полный набор ошибок, а не срез этого шага: сводка на «Проверке» именно про другие шаги.
      errors = state.errors,
      isSaving = state.isSaving,
      testStatus = testStatus,
      testStreamingText = testStreamingText,
      testToolTrace = testToolTrace,
      onSetExecutionMode = onSetExecutionMode,
      onSendTestMessage = onSendTestMessage,
      onPublish = onPublish,
      onArchive = onArchive,
      modifier = Modifier.fillMaxSize(),
    )
  }
}

@Composable
private fun BuilderBottomBar(
  saveError: String?,
  showNext: Boolean,
  canAdvance: Boolean,
  glassState: GlassState,
  onBack: () -> Unit,
  onNext: () -> Unit,
) {
  GlassSurface(state = glassState, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      saveError?.let {
        Text(
          text = it,
          color = MaterialTheme.colorScheme.error,
          modifier = Modifier.padding(bottom = 8.dp),
        )
      }
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
          onClick = onBack,
          modifier = Modifier.testTag("agent_builder_back"),
        ) { Text("Назад") }
        Box(modifier = Modifier.weight(1f))
        if (showNext) {
          Button(
            onClick = onNext,
            enabled = canAdvance,
            modifier = Modifier.testTag("agent_builder_next"),
          ) { Text("Далее") }
        }
      }
    }
  }
}
