package com.aiopenpocket.ui.components

// @trace ai-academy-onboarding-5d51bb

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import com.aiopenpocket.ui.components.glass.GlassState
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
        )
      },
      bottomBar = bottomBar,
      floatingActionButton = floatingActionButton,
      content = content,
    )
  }
}
