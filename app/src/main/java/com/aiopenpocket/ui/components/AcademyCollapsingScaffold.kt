package com.aiopenpocket.ui.components

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.components.glass.GlassState
import com.aiopenpocket.ui.components.glass.GlassSurface
import com.aiopenpocket.ui.components.glass.auroraBackground
import com.aiopenpocket.ui.components.glass.glassSource

/**
 * Каркас экрана с коллапсящимся тулбаром: крупный засечный заголовок сверху, который при
 * скролле списка плавно сворачивается в компактную закреплённую полоску (классический
 * collapsing toolbar). Контент должен быть скроллируемым (LazyColumn / verticalScroll),
 * чтобы вложенный скролл управлял сворачиванием.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademyCollapsingScaffold(
  title: String,
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.background,
  glassState: GlassState? = null,
  navigationIcon: @Composable () -> Unit = {},
  actions: @Composable RowScope.() -> Unit = {},
  bottomBar: @Composable () -> Unit = {},
  floatingActionButton: @Composable () -> Unit = {},
  content: @Composable (PaddingValues) -> Unit,
) {
  val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
  // Когда передан [glassState], под каркас кладём отдельный matchParentSize-слой-источник авроры
  // (как на вкладке «Чат»/HomeScreen): каркас и его бары прозрачны, плавающий навбар размывает аврору.
  val effectiveContainer = if (glassState != null) Color.Transparent else containerColor
  Box(Modifier.fillMaxSize()) {
    if (glassState != null) {
      Box(
        Modifier
          .matchParentSize()
          .auroraBackground()
          .glassSource(glassState),
      )
    }
    Scaffold(
      modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
      containerColor = effectiveContainer,
      topBar = {
        val bar: @Composable () -> Unit = {
          LargeTopAppBar(
            title = {
              Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            },
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
              containerColor = effectiveContainer,
              // Тот же цвет в свёрнутом состоянии — без elevation-tint (или прозрачный поверх авроры).
              scrolledContainerColor = effectiveContainer,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
              navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
              actionIconContentColor = MaterialTheme.colorScheme.onSurface,
            ),
            scrollBehavior = scrollBehavior,
            // В стеклянном режиме инсет статус-бара вынесен на саму таблетку (ниже), поэтому сам
            // бар свой инсет НЕ добавляет — иначе он задвоится и таблетка уедет под часы/вырез.
            // Тот же приём, что у плавающей шапки чата.
            windowInsets = if (glassState != null) WindowInsets(0, 0, 0, 0) else TopAppBarDefaults.windowInsets,
          )
        }
        if (glassState != null) {
          // Плавающий стеклянный остров — но ПРОЯВЛЯЮЩИЙСЯ по мере сворачивания бара.
          //
          // Наверху списка (бар развёрнут) контент под него ещё не заехал: подложка не нужна, а
          // большой заголовок в залитой таблетке читается тяжёлым тёмным слабом и спорит с идеей
          // «контент занимает всё». Поэтому крупный заголовок лежит прямо на полотне. Как только
          // список поехал под бар — стекло набирает плотность и защищает читаемость. Ровно так
          // ведёт себя и системный iOS-бар.
          //
          // collapsedFraction читаем ВНУТРИ graphicsLayer (фаза рисования), а не в композиции:
          // иначе каждый кадр скролла рекомпозировал бы весь топбар. Тот же приём, что у ленты чата.
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .statusBarsPadding(),
          ) {
            GlassSurface(
              state = glassState,
              shape = TOP_ISLAND_SHAPE,
              modifier = Modifier
                .matchParentSize()
                .padding(horizontal = 12.dp)
                .padding(top = 8.dp, bottom = 8.dp)
                .graphicsLayer { alpha = scrollBehavior.state.collapsedFraction }
                .shadow(16.dp, TOP_ISLAND_SHAPE, clip = false),
            ) {}
            bar()
          }
        } else {
          bar()
        }
      },
      bottomBar = bottomBar,
      floatingActionButton = floatingActionButton,
      content = { padding ->
        if (glassState != null) {
          // Контент — ТОЖЕ источник для стекла: тогда верхний остров и нижний навбар размывают
          // настоящий список, а не одну статичную аврору (иначе стекло читается плоской плашкой —
          // см. ChatScreen.kt:396-400). Слот content у Scaffold баров не содержит, петли нет.
          Box(
            Modifier
              .fillMaxSize()
              .glassSource(glassState),
          ) {
            content(padding)
          }
        } else {
          content(padding)
        }
      },
    )
  }
}

/** Форма верхнего плавающего острова — та же «таблетка», что у баров чата и нижней навигации. */
private val TOP_ISLAND_SHAPE = RoundedCornerShape(24.dp)
