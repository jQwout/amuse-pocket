package com.aiopenpocket.agent.core.prompt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regression guard: loadModePrompt silently returns null if one of these assets goes missing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrePromptAssemblerModeAssetsTest {

  @Test
  fun modePromptAssetsExistAndAreNonBlank() {
    val assets = ApplicationProvider.getApplicationContext<Context>().assets
    listOf(
      "prompts/modes/plan_planning.md",
      "prompts/modes/plan_executing.md",
      "prompts/modes/goal.md",
    ).forEach { path ->
      val text = assets.open(path).bufferedReader().use { it.readText() }
      assertTrue("$path should not be blank", text.isNotBlank())
    }
  }
}
