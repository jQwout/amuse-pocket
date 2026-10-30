package com.aiopenpocket.ui.agents.builder

// @trace user-agent-builder-848153

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.aiopenpocket.agent.core.policy.ExecutionMode
import com.aiopenpocket.agent.core.policy.ToolApprovalRequest
import com.aiopenpocket.agent.loop.ToolTrace
import com.aiopenpocket.agent.loop.TurnStatus
import com.aiopenpocket.i18n.tr
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
 * Reuses the onboarding wizard's own chrome pattern (`OnboardingScreen.kt`) rather than
 * [com.aiopenpocket.ui.components.AcademyCollapsingScaffold] — that scaffold's collapsing toolbar
 * exists for scrollable list screens (Agents/Roadmap), and doesn't fit a fixed six-step wizard whose
 * steps aren't all scrollable lists themselves.
 *
 * The chrome follows the Liquid Glass concept (spec
 * `docs/superpowers/specs/2026-07-28-onboarding-liquid-glass-design.md`): the step's canvas takes the
 * WHOLE screen, and the header with progress and the "Back/Next" buttons are glass islands floating
 * on top of it. There used to be a Material3 [androidx.compose.material3.Scaffold] here with
 * topBar/bottomBar: they ate space, the content lived in what was left and never went under the glass
 * — there was nothing to blur but the static aurora.
 */
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
  /** A confirmation request from a hook in the test turn — see `AgentBuilderTestViewModel.testPendingApproval`. */
  testPendingApproval: ToolApprovalRequest? = null,
  onResolveTestApproval: (approved: Boolean) -> Unit = {},
  /** "Open chat" after publishing; null — no button (the route cannot navigate to the chat). */
  onOpenChat: (() -> Unit)? = null,
  /** "Open Settings" on the test run's no-key message; null — no button. */
  onOpenSettings: (() -> Unit)? = null,
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
  // canAdvance is no longer computed here: "Next" is always tappable and triggers validation itself
  // (see BuilderBottomBar and AgentBuilderViewModel.nextStep). The gate lives in the ViewModel, not in the button.

  // The floating islands' heights are measured (onSizeChanged) and turned into the canvas paddings —
  // otherwise the top/bottom of the step would hide under the glass forever. The same trick as in
  // OnboardingScreen/ChatScreen.
  val density = LocalDensity.current
  var topIslandHeightPx by remember { mutableIntStateOf(0) }
  var bottomIslandHeightPx by remember { mutableIntStateOf(0) }
  val topPad = with(density) { topIslandHeightPx.toDp() }
  val bottomPad = with(density) { bottomIslandHeightPx.toDp() }

  Box(modifier = modifier.fillMaxSize()) {
    // The aurora is a SEPARATE source layer behind everything. glassSource cannot be hung on the root Box:
    // the source would capture the islands themselves — a loop (see ChatScreen.kt:396-400).
    Box(
      Modifier
        .matchParentSize()
        .auroraBackground()
        .glassSource(glassState),
    )

    // THE CANVAS: the step body across the whole screen, going under the islands. It is a source too — so
    // the glass blurs the REAL shape of the step, not just the aurora.
    Box(
      Modifier
        .fillMaxSize()
        .glassSource(glassState),
    ) {
      // Without a Scaffold/Surface above the canvas the ambient LocalContentColor stays near-black, and the
      // step headings drown on the dark aurora. The color is set once for the whole canvas (as in
      // onboarding).
      CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
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
          testPendingApproval = testPendingApproval,
          onResolveTestApproval = onResolveTestApproval,
          onOpenChat = onOpenChat,
          onOpenSettings = onOpenSettings,
          contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
        )
      }
    }

    // The TOP island: the wizard's title, the close cross and the step progress. statusBarsPadding lives
    // on the pill itself, so the height measured from outside already includes the status bar inset.
    Column(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .onSizeChanged { topIslandHeightPx = it.height },
    ) {
      BuilderTopIsland(progress = progress, glassState = glassState, onExit = onExit)
    }

    // The BOTTOM island: the save error and "Back/Next". onSizeChanged comes BEFORE windowInsetsPadding
    // so the measured height INCLUDES the bottom inset (navbar/IME): safeDrawing bottom = max(navbar,
    // keyboard), without counting twice.
    Column(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .onSizeChanged { bottomIslandHeightPx = it.height }
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
      BuilderBottomBar(
        saveError = state.saveError,
        showNext = state.step != BuilderStep.TEST,
        glassState = glassState,
        onBack = onBack,
        onNext = onNext,
      )
    }
  }
}

/**
 * The wizard's top glass island: a stable title (each step's body already has the step name as an H1 —
 * no need to repeat it in the toolbar), the exit cross and the progress as a thin capsule. The progress
 * value is the same as before: `(step + 1) / total`.
 */
@Composable
private fun BuilderTopIsland(
  progress: Float,
  glassState: GlassState,
  onExit: () -> Unit,
) {
  GlassSurface(
    state = glassState,
    shape = ISLAND_SHAPE,
    modifier = Modifier
      .fillMaxWidth()
      .statusBarsPadding()
      .padding(horizontal = 12.dp)
      .padding(top = 8.dp, bottom = 8.dp)
      .shadow(16.dp, ISLAND_SHAPE, clip = false),
  ) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onExit) {
          Icon(imageVector = Icons.Default.Close, contentDescription = tr("Закрыть", "Close"))
        }
        Spacer(Modifier.width(4.dp))
        Text(
          text = tr("Конструктор агента", "Agent builder"),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Spacer(Modifier.height(10.dp))
      LinearProgressIndicator(
        progress = { progress },
        modifier = Modifier
          .fillMaxWidth()
          .height(4.dp)
          .clip(CircleShape),
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
      )
    }
  }
}

/** The shape of the floating islands — the same "pill" as onboarding, the chat bars and the bottom navigation. */
private val ISLAND_SHAPE = RoundedCornerShape(24.dp)

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
  testPendingApproval: ToolApprovalRequest?,
  onResolveTestApproval: (approved: Boolean) -> Unit,
  onOpenChat: (() -> Unit)?,
  onOpenSettings: (() -> Unit)?,
  contentPadding: PaddingValues,
) {
  // Validation "on the button": a step's inline errors are shown only after "Next" has run into them
  // (see AgentBuilderUiState.showErrors). Otherwise a fresh draft greeted the user with red lines under
  // every empty field they had not touched yet. The "Check" step is NOT affected — there the summary of
  // blockers is the point of the screen, it is drawn from state.errors directly.
  val stepErrors = if (state.showErrors) {
    AgentBuilderValidator.errorsForStep(state.errors, state.step)
  } else {
    emptyList()
  }
  when (state.step) {
    BuilderStep.IDENTITY -> IdentityStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
      contentPadding = contentPadding,
    )

    // The only NON-scrollable step: there is nothing to slide under the glass, so the padding is applied
    // outside, through the modifier, not as a parameter inside the scroll.
    BuilderStep.INSTRUCTIONS -> InstructionsStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize().padding(contentPadding),
    )

    BuilderStep.CAPABILITIES -> CapabilitiesStep(
      draft = state.draft,
      errors = stepErrors,
      availableSkillIds = availableSkillIds,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
      contentPadding = contentPadding,
    )

    BuilderStep.LOOP -> LoopModesStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
      contentPadding = contentPadding,
    )

    BuilderStep.HOOKS -> HooksStep(
      draft = state.draft,
      errors = stepErrors,
      onUpdateDraft = onUpdateDraft,
      modifier = Modifier.fillMaxSize(),
      contentPadding = contentPadding,
    )

    BuilderStep.TEST -> TestAndPublishStep(
      draft = state.draft,
      executionMode = state.executionMode,
      canPublish = state.canPublish,
      // The full set of errors, not this step's slice: the summary on "Check" is exactly about the other steps.
      errors = state.errors,
      isSaving = state.isSaving,
      testStatus = testStatus,
      testStreamingText = testStreamingText,
      testToolTrace = testToolTrace,
      onSetExecutionMode = onSetExecutionMode,
      onSendTestMessage = onSendTestMessage,
      onPublish = onPublish,
      onArchive = onArchive,
      justPublished = state.justPublished,
      testPendingApproval = testPendingApproval,
      onResolveTestApproval = onResolveTestApproval,
      onOpenChat = onOpenChat,
      onOpenSettings = onOpenSettings,
      modifier = Modifier.fillMaxSize(),
      contentPadding = contentPadding,
    )
  }
}

@Composable
private fun BuilderBottomBar(
  saveError: String?,
  showNext: Boolean,
  glassState: GlassState,
  onBack: () -> Unit,
  onNext: () -> Unit,
) {
  // A floating island, not a strip welded to the bottom: side paddings + rounding + a shadow, like
  // onboarding and the bottom navigation.
  GlassSurface(
    state = glassState,
    shape = ISLAND_SHAPE,
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp)
      .padding(top = 8.dp, bottom = 12.dp)
      .shadow(16.dp, ISLAND_SHAPE, clip = false),
  ) {
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
        ) { Text(tr("Назад", "Back")) }
        Box(modifier = Modifier.weight(1f))
        if (showNext) {
          // "Next" is ALWAYS tappable: it is the validation trigger. The button used to go dim on blocking
          // errors — together with errors shown right away that made a dead screen with nothing to tap
          // and no idea what was wanted. Now a tap either moves on or shows what is in the way
          // (AgentBuilderViewModel.nextStep).
          Button(
            onClick = onNext,
            modifier = Modifier.testTag("agent_builder_next"),
          ) { Text(tr("Далее", "Next")) }
        }
      }
    }
  }
}
