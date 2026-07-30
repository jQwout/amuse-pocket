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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
 * Reuses the onboarding wizard's own chrome pattern (`OnboardingScreen.kt`) rather than
 * [com.aiopenpocket.ui.components.AcademyCollapsingScaffold] — that scaffold's collapsing toolbar
 * exists for scrollable list screens (Agents/Roadmap), and doesn't fit a fixed six-step wizard whose
 * steps aren't all scrollable lists themselves.
 *
 * Обвязка — в концепции Liquid Glass (спека `docs/superpowers/specs/2026-07-28-onboarding-liquid-glass-design.md`):
 * полотно шага занимает ВЕСЬ экран, а заголовок с прогрессом и кнопки «Назад/Далее» — стеклянные
 * острова, плавающие поверх него. Раньше здесь был Material3 [androidx.compose.material3.Scaffold]
 * с topBar/bottomBar: они съедали место, контент жил в остатке и под стекло не заезжал — размывать
 * было нечего, кроме статичной авроры.
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
  // canAdvance здесь больше не считается: «Далее» всегда нажимаема и сама запускает валидацию
  // (см. BuilderBottomBar и AgentBuilderViewModel.nextStep). Гейт живёт во ViewModel, а не в кнопке.

  // Высоты плавающих островов меряем (onSizeChanged) и превращаем в отступы полотна — иначе верх/низ
  // шага навсегда прячутся под стеклом. Тот же приём, что в OnboardingScreen/ChatScreen.
  val density = LocalDensity.current
  var topIslandHeightPx by remember { mutableIntStateOf(0) }
  var bottomIslandHeightPx by remember { mutableIntStateOf(0) }
  val topPad = with(density) { topIslandHeightPx.toDp() }
  val bottomPad = with(density) { bottomIslandHeightPx.toDp() }

  Box(modifier = modifier.fillMaxSize()) {
    // Аврора — ОТДЕЛЬНЫЙ слой-источник позади всего. Повесить glassSource на корневой Box нельзя:
    // источник захватил бы и сами острова — петля (см. ChatScreen.kt:396-400).
    Box(
      Modifier
        .matchParentSize()
        .auroraBackground()
        .glassSource(glassState),
    )

    // ПОЛОТНО: тело шага во весь экран, уходит под острова. Тоже источник — тогда стекло размывает
    // НАСТОЯЩУЮ форму шага, а не только аврору.
    Box(
      Modifier
        .fillMaxSize()
        .glassSource(glassState),
    ) {
      // Без Scaffold/Surface над полотном ambient LocalContentColor остаётся near-black и заголовки
      // шагов тонут на тёмной ауроре. Задаём цвет один раз на всё полотно (как в онбординге).
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
          contentPadding = PaddingValues(top = topPad, bottom = bottomPad),
        )
      }
    }

    // ВЕРХНИЙ остров: заголовок визарда, крестик и прогресс по шагам. statusBarsPadding живёт на
    // самой таблетке, поэтому измеренная снаружи высота уже включает инсет статус-бара.
    Column(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .onSizeChanged { topIslandHeightPx = it.height },
    ) {
      BuilderTopIsland(progress = progress, glassState = glassState, onExit = onExit)
    }

    // НИЖНИЙ остров: ошибка сохранения и «Назад/Далее». onSizeChanged СТОИТ ПЕРЕД
    // windowInsetsPadding, чтобы измеренная высота ВКЛЮЧАЛА нижний инсет (навбар/IME):
    // safeDrawing bottom = max(навбар, клавиатура), без двойного счёта.
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
 * Верхний стеклянный остров визарда: стабильный заголовок (имя шага уже есть H1 в теле каждого шага —
 * дублировать его в тулбаре незачем), крестик выхода и прогресс тонкой капсулой. Значение прогресса
 * прежнее: `(шаг + 1) / всего`.
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
    // tint убран (по умолчанию GlassSurface кладёт белёсую плёнку LightTint поверх блюра). У визарда
    // остров плотный ВСЕГДА — в отличие от списочных экранов, где стекло проявляется по скроллу, — и
    // плёнка вымывала зелень авроры: таблетка читалась нейтральным тёмным слабом на цветном экране.
    // Чистый блюр сохраняет оттенок фона и при этом остаётся подложкой для текста.
    tint = Color.Transparent,
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
          Icon(imageVector = Icons.Default.Close, contentDescription = "Закрыть")
        }
        Spacer(Modifier.width(4.dp))
        Text(
          text = "Конструктор агента",
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

/** Форма плавающих островов — та же «таблетка», что у онбординга, баров чата и нижней навигации. */
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
  contentPadding: PaddingValues,
) {
  // Валидация «по кнопке»: инлайн-ошибки шага показываем только после того, как «Далее» упёрлось в
  // них (см. AgentBuilderUiState.showErrors). Иначе свежий черновик встречал пользователя красными
  // строками под каждым пустым полем, которого он ещё не трогал. Шаг «Проверка» это НЕ затрагивает —
  // там сводка блокеров и есть смысл экрана, она рисуется из state.errors напрямую.
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

    // Единственный НЕ прокручиваемый шаг: заезжать под стекло нечему, поэтому отступ снаружи,
    // через modifier, а не параметром внутрь прокрутки.
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
  // Плавающий остров, а не приваренная к низу полоса: боковые отступы + скругление + тень, как у
  // онбординга и нижней навигации.
  GlassSurface(
    state = glassState,
    shape = ISLAND_SHAPE,
    // Тот же чистый блюр, что у верхнего острова — см. BuilderTopIsland.
    tint = Color.Transparent,
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
        ) { Text("Назад") }
        Box(modifier = Modifier.weight(1f))
        if (showNext) {
          // «Далее» ВСЕГДА нажимаема: она и есть триггер валидации. Раньше кнопка гасла на
          // блокирующих ошибках — вместе с ошибками, показанными сразу, это давало мёртвый экран,
          // на котором нечего нажать и непонятно, чего от тебя хотят. Теперь нажатие либо ведёт
          // дальше, либо показывает, что мешает (AgentBuilderViewModel.nextStep).
          Button(
            onClick = onNext,
            modifier = Modifier.testTag("agent_builder_next"),
          ) { Text("Далее") }
        }
      }
    }
  }
}
