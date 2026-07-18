package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * «Слабое» стекло — имитация без blur (tint + блик-бордер). Безопасно внутри
 * item'ов LazyColumn (никакого RenderEffect на прокрутке).
 * @trace glass-design-system-21a3fe
 */
@Composable
fun GlassCard(
  modifier: Modifier = Modifier,
  shape: Shape = GlassTokens.CardShape,
  onClick: (() -> Unit)? = null,
  tint: Color = GlassTokens.LightTint,
  content: @Composable ColumnScope.() -> Unit,
) {
  val base = modifier
    .clip(shape)
    .background(tint)
    .border(1.dp, GlassTokens.BorderBrush, shape)
  Column(
    modifier = if (onClick != null) base.clickable(onClick = onClick) else base,
    content = content,
  )
}

/**
 * Рецепт [GlassCard] как модификатор — для мест, где обернуть контент в [GlassCard] нельзя
 * (кастомная вёрстка на Column/Box, TextField): скругление + полупрозрачный tint + рим-блик.
 * Тот же вид, что у карточек на вкладке «Чат».
 * @trace glass-design-system-21a3fe
 */
@Composable
fun Modifier.glassCardSurface(shape: Shape = GlassTokens.CardShape): Modifier =
  this
    .clip(shape)
    .background(GlassTokens.LightTint)
    .border(1.dp, GlassTokens.BorderBrush, shape)
