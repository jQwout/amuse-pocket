package com.aiopenpocket

// @trace vybor-provaydera-i-zhivoy-katalog-modeley-fe9a53

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No provider key travels inside the build (decision of Oct 30, 2026): the person enters their own Meta
 * Model API key in the app. BuildConfig is compiled into the artifact, and a string in it can be read with
 * one unzip, so this looks at the real generated class, not at the Gradle file.
 *
 * Two complementary checks guard the same invariant elsewhere: `tools/check.py` T4 reads
 * `app/build.gradle.kts`, and T4a scans the built APK. This one fails inside the ordinary unit-test run,
 * where a developer will see it before any script is run.
 */
class BuildConfigHasNoKeysTest {

  private val fields = BuildConfig::class.java.declaredFields
    .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) }

  /** What the Android Gradle plugin puts into BuildConfig on its own. Anything else is somebody's addition. */
  private val standard = setOf("DEBUG", "APPLICATION_ID", "BUILD_TYPE", "FLAVOR", "VERSION_CODE", "VERSION_NAME")

  @Test
  fun `BuildConfig holds only the fields the Android plugin generates`() {
    val extra = fields.map { it.name }.filterNot { it in standard }
    assertEquals("a custom BuildConfig field appeared: $extra", emptyList<String>(), extra)
  }

  @Test
  fun `no BuildConfig field is named like a key and none holds a key-shaped value`() {
    val keyName = Regex("KEY|TOKEN|SECRET|PASSWORD", RegexOption.IGNORE_CASE)
    val keyValue = Regex("sk-[A-Za-z0-9_-]{16,}|nvapi-|AIza[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|MY_[A-Z_]*KEY")
    for (field in fields) {
      assertTrue("${field.name} is named like a key", !keyName.containsMatchIn(field.name))
      val value = field.apply { isAccessible = true }.get(null)?.toString().orEmpty()
      assertTrue("${field.name} holds a key-shaped value", !keyValue.containsMatchIn(value))
    }
  }
}
