package com.aiopenpocket.ui.components.glass

// @trace glass-design-system-21a3fe

import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTokensTest {

  @Test fun strongTintIsMoreOpaqueThanLight() {
    // StrongTint/LightTint теперь @Composable-геттеры (зависят от темы) — проверяем сырые
    // per-theme константы в обеих темах.
    assertTrue(GlassTokens.StrongTintDark.alpha > GlassTokens.LightTintDark.alpha)
    assertTrue(GlassTokens.StrongTintLight.alpha > GlassTokens.LightTintLight.alpha)
  }

  @Test fun blurRadiusIs20dp() {
    assertEquals(20.dp, GlassTokens.BlurRadius)
  }

  @Test fun hasThreeAuroraSpots() {
    assertEquals(3, GlassTokens.AuroraSpots.size)
  }

  @Test fun auroraCentersAreWithinUnitSquare() {
    GlassTokens.AuroraSpots.forEach { spot ->
      assertTrue(spot.center.x in 0f..1f)
      assertTrue(spot.center.y in 0f..1f)
    }
  }

  @Test fun auroraRadiusFractionsAreValid() {
    // Guards AuroraBackground's radius>0 invariant: a spot with radiusFraction<=0
    // would make drawCircle's RadialGradient throw (see Modifier.auroraBackground).
    GlassTokens.AuroraSpots.forEach { spot ->
      assertTrue(spot.radiusFraction > 0f)
      assertTrue(spot.radiusFraction in 0f..1f)
    }
  }
}
