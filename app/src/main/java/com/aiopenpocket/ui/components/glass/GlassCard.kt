package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens

/**
 * "Weak" glass — an imitation without blur (tint + a highlight border). Safe inside LazyColumn items (no
 * RenderEffect while scrolling).
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
  ) {
    // A Column, not a Surface, so nothing set the content colour: a chat title or a delete icon without its
    // own colour fell back to Compose's default black, near-invisible on these cards in the dark theme
    // (chat list, Android 14, 29 Oct 2026).
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
  }
}

/**
 * The [GlassCard] recipe as a modifier — for places where the content cannot be wrapped in a [GlassCard]
 * (custom layout on Column/Box, TextField): rounding + a translucent tint + a rim highlight. The same look as
 * the cards on the "Chat" tab.
 * @trace glass-design-system-21a3fe
 */
@Composable
fun Modifier.glassCardSurface(shape: Shape = GlassTokens.CardShape): Modifier =
  this
    .clip(shape)
    .background(GlassTokens.LightTint)
    .border(1.dp, GlassTokens.BorderBrush, shape)
