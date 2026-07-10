package com.aiopenpocket.ui.components.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.aiopenpocket.ui.theme.MyApplicationTheme
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
      MyApplicationTheme {
        val state = rememberGlassState()
        Box(Modifier.fillMaxSize().auroraBackground()) {
          LazyColumn(Modifier.fillMaxSize().glassSource(state)) {
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
}
