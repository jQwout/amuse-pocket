package com.aiopenpocket.ui.components.glass

// @trace glass-design-system-21a3fe

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.MyApplicationTheme
import com.aiopenpocket.ui.theme.ThemeMode
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [36])
class GlassScreenshotTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test fun glass_components_over_aurora() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        val state = rememberGlassState()
        Box(Modifier.fillMaxSize().auroraBackground()) {
          LazyColumn(modifier = Modifier.fillMaxSize().glassSource(state), contentPadding = PaddingValues(top = 72.dp)) {
            item {
              GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Light glass card", Modifier.padding(16.dp))
              }
            }
          }
          GlassSurface(
            state = state,
            modifier = Modifier.fillMaxWidth().height(64.dp),
          ) { Text("Strong glass bar", Modifier.padding(16.dp)) }
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/glass_components.png")
  }

  /**
   * The same frame in the light theme — executable proof of the switch: a paper base, the aurora turned
   * off (an empty list of blobs), the card tints flipped to dark.
   */
  @Test fun glass_components_light() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.LIGHT) {
        val state = rememberGlassState()
        Box(Modifier.fillMaxSize().auroraBackground()) {
          LazyColumn(modifier = Modifier.fillMaxSize().glassSource(state), contentPadding = PaddingValues(top = 72.dp)) {
            item {
              GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Light glass card", Modifier.padding(16.dp))
              }
            }
          }
          GlassSurface(
            state = state,
            modifier = Modifier.fillMaxWidth().height(64.dp),
          ) { Text("Strong glass bar", Modifier.padding(16.dp)) }
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/glass_components_light.png")
  }

  @Test
  @Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [30])
  fun glass_components_over_aurora_api30() {
    composeTestRule.setContent {
      MyApplicationTheme(mode = ThemeMode.DARK) {
        val state = rememberGlassState()
        Box(Modifier.fillMaxSize().auroraBackground()) {
          LazyColumn(modifier = Modifier.fillMaxSize().glassSource(state), contentPadding = PaddingValues(top = 72.dp)) {
            item {
              GlassCard(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Light glass card", Modifier.padding(16.dp))
              }
            }
          }
          GlassSurface(
            state = state,
            modifier = Modifier.fillMaxWidth().height(64.dp),
          ) { Text("Strong glass bar", Modifier.padding(16.dp)) }
        }
      }
    }
    composeTestRule.onRoot()
      .captureRoboImage(filePath = "src/test/screenshots/glass_components_api30.png")
  }
}
