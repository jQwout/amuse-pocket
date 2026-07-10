package com.aiopenpocket.ui.components.glass

import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.GlassTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTokensTest {

  @Test fun strongTintIsMoreOpaqueThanLight() {
    assertTrue(GlassTokens.StrongTint.alpha > GlassTokens.LightTint.alpha)
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
}
