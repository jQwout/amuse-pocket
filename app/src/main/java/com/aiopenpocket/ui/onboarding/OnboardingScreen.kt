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
import com.aiopenpocket.ui.theme.MyApplicationTheme
import dev.zacsweers.metrox.viewmodel.metroViewModel

private val ONBOARDING_STEP_ORDER = OnboardingStep.entries.toList()

/** Форма плавающих стеклянных островов — та же «таблетка», что у баров чата. */
private val ISLAND_SHAPE = RoundedCornerShape(24.dp)

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
 * Онбординг в концепции Liquid Glass: полотно занимает ВЕСЬ экран, а обвязка — стеклянные острова,
 * плавающие поверх него (спека `docs/superpowers/specs/2026-07-28-onboarding-liquid-glass-design.md`).
 *
 * Раньше здесь был Material3 [androidx.compose.material3.Scaffold] с topBar/bottomBar: они СЪЕДАЮТ
 * место, контент жил в остатке через `innerPadding` и под стекло не заезжал — размывать было нечего,
 * кроме статичной авроры, отсюда «плоская плашка». Сейчас острова лежат оверлеями поверх полотна, их
 * высоты меряются и превращаются в отступы контента.
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

    // Высоты плавающих островов меряем (onSizeChanged) и превращаем в отступы полотна — иначе
    // верх/низ шага навсегда прячутся под стеклом. Тот же приём, что в ChatScreen.
    val density = LocalDensity.current
    var topIslandHeightPx by remember { mutableIntStateOf(0) }
    var bottomIslandHeightPx by remember { mutableIntStateOf(0) }
    val topPad = with(density) { topIslandHeightPx.toDp() }
    val bottomPad = with(density) { bottomIslandHeightPx.toDp() }

    Box(Modifier.fillMaxSize()) {
        // Аврора — ОТДЕЛЬНЫЙ слой-источник, лежащий позади всего. Повесить glassSource на корневой
        // Box нельзя: источник захватил бы и сами острова — петля (см. ChatScreen.kt:396-400).
        Box(
            Modifier
                .matchParentSize()
                .auroraBackground()
                .glassSource(glassState),
        )

        // ПОЛОТНО: тело шага занимает весь экран и уходит под острова. Тоже помечено источником —
        // тогда стекло размывает НАСТОЯЩИЙ контент, а не только аврору.
        Box(
            Modifier
                .fillMaxSize()
                .glassSource(glassState),
        ) {
            // Над полотном нет Scaffold/Surface, поэтому ambient LocalContentColor остаётся
            // дефолтным (near-black) и заголовки шагов тонут на тёмной ауроре. Дефект НЕ новый:
            // AboutMeStep уже лечил его у себя явным цветом (см. его комментарий), а «Выбери язык»
            // тонул и до этой переделки. Задаём цвет один раз на всё полотно — тогда каждому шагу
            // не нужно чинить это самостоятельно.
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

        // ВЕРХНИЙ остров: заголовок + шкала прогресса. statusBarsPadding живёт на самой таблетке
        // (ребёнок этой Column), поэтому измеренная снаружи высота уже включает инсет статус-бара.
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

        // НИЖНИЙ остров: ошибка + «Назад»/«Готово». onSizeChanged СТОИТ ПЕРЕД windowInsetsPadding,
        // чтобы измеренная высота ВКЛЮЧАЛА нижний инсет: safeDrawing bottom = max(навбар, клавиатура),
        // без двойного счёта — navigationBars/imePadding поверх НЕ добавляем.
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
 * Верхний стеклянный остров: заголовок и прогресс мастера. Прогресс — тонкая капсула ВНУТРИ острова
 * вместо прежней материаловской полосы во всю ширину экрана: полноширинная линия разрезала полотно
 * и спорила с плавающей формой. Значение не меняется — та же доля `(шаг + 1) / всего`.
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

/** Нижний стеклянный остров: сообщение об ошибке и кнопки шага. */
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
 * Тело шага. Отступы под острова прокидываются РАЗНЫМИ путями и это принципиально: у прокручиваемого
 * шага они должны лежать ВНУТРИ скролла (иначе инсетится вьюпорт и контент под стекло не заезжает),
 * у статичных достаточно отступа снаружи.
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
        // Статичные шаги: прокрутки нет, заезжать под стекло нечему — отступ снаружи.
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

        // PROFILE — последний шаг мастера («Готово» завершает настройку) и единственный
        // прокручиваемый: отступы уходят ВНУТРЬ, под verticalScroll.
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
