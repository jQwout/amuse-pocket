package com.aiopenpocket.ui.onboarding

// @trace ai-academy-onboarding-5d51bb
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aiopenpocket.i18n.tr
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.glassSource
import com.aiopenpocket.ui.components.glass.rememberGlassState
import com.aiopenpocket.ui.onboarding.components.AboutMeStep
import com.aiopenpocket.ui.onboarding.components.IntroStreamingStep
import com.aiopenpocket.ui.onboarding.components.LanguageSelectionStep
import com.aiopenpocket.ui.onboarding.components.MetaKeyStep
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val ONBOARDING_STEP_ORDER = OnboardingStep.entries.toList()

/** The shape of the floating glass islands — the same "pill" as the chat bars. */
private val ISLAND_SHAPE = RoundedCornerShape(24.dp)

@Composable
fun OnboardingScreen(
    // Non-null when reached from the roadmap as a re-edit; closes back to the previous screen.
    onClose: (() -> Unit)? = null,
) {
    val viewModel: OnboardingViewModel = metroViewModel()
    val state by viewModel.state.collectAsState()
    val editMode = onClose != null

    // In edit mode the saved values are pulled in, otherwise Complete would overwrite the profile with empty fields.
    LaunchedEffect(editMode) { if (editMode) viewModel.loadExistingProfile() }

    LaunchedEffect(state.currentStep) {
        if (state.currentStep == OnboardingStep.INTRO) {
            viewModel.handleIntent(OnboardingIntent.StartIntroStreaming)
        }
    }

    // In edit mode the flag is already set, so navigate back ourselves once saved.
    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted && editMode) onClose.invoke()
    }

    OnboardingContent(
        state = state,
        editMode = editMode,
        onClose = onClose,
        onIntent = viewModel::handleIntent,
    )
}

/**
 * Onboarding in the Liquid Glass concept: the canvas takes the WHOLE screen, and the chrome is glass islands
 * floating on top of it (spec `docs/superpowers/specs/2026-07-28-onboarding-liquid-glass-design.md`).
 *
 * There used to be a Material3 [androidx.compose.material3.Scaffold] here with topBar/bottomBar: they EAT
 * space, the content lived in what was left via `innerPadding` and never slid under the glass — there was
 * nothing to blur but the static aurora, hence the "flat slab". Now the islands lie as overlays on top of the
 * canvas, and their heights are measured and turned into content paddings.
 *
 * internal (not private): the Task 5 glass screenshot test renders this directly on fake
 * OnboardingUiState — it takes no ViewModel, so a harness composable would just duplicate it.
 */
@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    editMode: Boolean,
    onClose: (() -> Unit)?,
    onIntent: (OnboardingIntent) -> Unit,
) {
    val glassState = rememberGlassState()
    val stepIndex = ONBOARDING_STEP_ORDER.indexOf(state.currentStep).coerceAtLeast(0)
    val progress = (stepIndex + 1) / ONBOARDING_STEP_ORDER.size.toFloat()

    // The floating islands' heights are measured (onSizeChanged) and turned into the canvas paddings — otherwise
    // the top/bottom of the step would hide under the glass forever. The same trick as in ChatScreen.
    val density = LocalDensity.current
    var topIslandHeightPx by remember { mutableIntStateOf(0) }
    var bottomIslandHeightPx by remember { mutableIntStateOf(0) }
    val topPad = with(density) { topIslandHeightPx.toDp() }
    val bottomPad = with(density) { bottomIslandHeightPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        // The aurora is a SEPARATE source layer lying behind everything. glassSource cannot be hung on the root Box:
        // the source would capture the islands themselves — a loop (see ChatScreen.kt:396-400).
        Box(
            Modifier
                .matchParentSize()
                .auroraBackground()
                .glassSource(glassState),
        )

        // THE CANVAS: the step body takes the whole screen and goes under the islands. It is marked as a source too —
        // then the glass blurs the REAL content, not just the aurora.
        Box(
            Modifier
                .fillMaxSize()
                .glassSource(glassState),
        ) {
            // There is no Scaffold/Surface above the canvas, so the ambient LocalContentColor stays the default
            // (near-black) and the step headings drown on the dark aurora. The defect is NOT new: AboutMeStep
            // already cured it locally with an explicit color (see its comment), and "Choose a language"
            // drowned even before this rework. The color is set once for the whole canvas — then no step needs
            // to fix it on its own.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                OnboardingStepBody(
                    state = state,
                    onIntent = onIntent,
                    topPad = topPad,
                    bottomPad = bottomPad,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // The TOP island: the title + the progress scale. statusBarsPadding lives on the pill itself (a child of
        // this Column), so the height measured from outside already includes the status bar inset.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { topIslandHeightPx = it.height },
        ) {
            OnboardingTopIsland(
                title = if (editMode) tr("Настройки урока 1", "Lesson 1 settings") else tr("Настройка", "Setup"),
                progress = progress,
                glassState = glassState,
                onClose = if (editMode) onClose else null,
            )
        }

        // The BOTTOM island: the error + "Back"/"Done". onSizeChanged comes BEFORE windowInsetsPadding so the
        // measured height INCLUDES the bottom inset: safeDrawing bottom = max(navbar, keyboard), without counting
        // twice — navigationBars/imePadding are NOT added on top.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomIslandHeightPx = it.height }
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
        ) {
            OnboardingBottomIsland(state = state, glassState = glassState, onIntent = onIntent)
        }
    }
}

/**
 * The top glass island: the wizard's title and progress. The progress is a thin capsule INSIDE the island
 * instead of the old Material bar across the full screen width: the full-width line cut the canvas and fought
 * the floating shape. The value does not change — the same share `(step + 1) / total`.
 */
@Composable
private fun OnboardingTopIsland(
    title: String,
    progress: Float,
    glassState: GlassState,
    onClose: (() -> Unit)?,
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
                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Закрыть", "Close"))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = title,
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

/** The bottom glass island: the error message and the step's buttons. */
@Composable
private fun OnboardingBottomIsland(
    state: OnboardingUiState,
    glassState: GlassState,
    onIntent: (OnboardingIntent) -> Unit,
) {
    GlassSurface(
        state = glassState,
        shape = ISLAND_SHAPE,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp, bottom = 12.dp)
            .shadow(16.dp, ISLAND_SHAPE, clip = false),
    ) {
        Column(modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp)) {
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
                    ) { Text(tr("Назад", "Back")) }
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
}

/**
 * The step body. The paddings for the islands are passed in DIFFERENT ways, and that matters: for a
 * scrollable step they must lie INSIDE the scroll (otherwise the viewport is inset and the content does not
 * slide under the glass), for static ones a padding outside is enough.
 */
@Composable
private fun OnboardingStepBody(
    state: OnboardingUiState,
    onIntent: (OnboardingIntent) -> Unit,
    topPad: Dp,
    bottomPad: Dp,
    modifier: Modifier,
) {
    when (state.currentStep) {
        // Static steps: there is no scrolling, nothing to slide under the glass — the padding goes outside.
        OnboardingStep.LANGUAGE -> LanguageSelectionStep(
            selectedLanguage = state.selectedLanguage,
            onSelectLanguage = { onIntent(OnboardingIntent.SelectLanguage(it)) },
            modifier = modifier.padding(top = topPad, bottom = bottomPad),
        )

        OnboardingStep.INTRO -> IntroStreamingStep(
            streamingText = state.introStreamingText,
            isStreaming = state.isIntroStreaming,
            modifier = modifier.padding(top = topPad, bottom = bottomPad),
        )

        // KEY scrolls too (the keyboard is up while typing), so its paddings go INSIDE, like PROFILE's.
        OnboardingStep.KEY -> MetaKeyStep(
            key = state.metaKeyInput,
            onKeyChange = { onIntent(OnboardingIntent.UpdateMetaKey(it)) },
            onSkip = { onIntent(OnboardingIntent.SkipMetaKey) },
            modifier = modifier,
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
        )

        // PROFILE is the wizard's last step ("Done" finishes the setup) and the only scrollable one: the paddings go
        // INSIDE, under verticalScroll.
        OnboardingStep.PROFILE -> AboutMeStep(
            personaId = state.selectedPersonaId,
            text = state.aboutMeText,
            isExtracting = state.isExtracting,
            extractionError = state.extractionError,
            onTextChange = { onIntent(OnboardingIntent.UpdateAboutMe(it)) },
            modifier = modifier,
            contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
        )
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
