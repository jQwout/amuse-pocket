plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.metro)
}

android {
  namespace = "com.aiopenpocket"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.aiopenpocket.hzwpqy"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Where Room puts the JSON schema snapshots (@Database(exportSchema = true)). The snapshots are committed:
  // the first real Migration will have to be written FROM the release schema, with nowhere to take it from if
  // they are not collected.
  ksp { arg("room.schemaLocation", "${projectDir}/schemas") }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    // debug.keystore is in .gitignore, so on a fresh clone it simply is not there — while the config referenced
    // it unconditionally, and the debug build failed at signing. The config is created only when the file
    // really exists; otherwise the standard debug key that AGP generates itself remains below.
    if (file("${rootDir}/debug.keystore").exists()) {
      create("debugConfig") {
        storeFile = file("${rootDir}/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // findByName, not getByName: the config above is created only when debug.keystore exists.
      signingConfig = signingConfigs.findByName("debugConfig") ?: signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
  kotlin {
    compilerOptions {
      jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

// NO provider key goes into BuildConfig, in any build type (decision of Oct 30, 2026). The app talks to the
// Meta Model API with a key the PERSON enters in onboarding or Settings (EncryptedSharedPreferences);
// nothing is bundled. That is why the Secrets Gradle Plugin that used to copy `.env` into BuildConfig is
// gone, together with its release guard: with no plugin there is no path from `.env` into the artifact,
// and BuildConfig is just the build type and the version. tools/check.py (T4) fails the build of the
// invariant if the plugin or a key-like buildConfigField ever comes back, and T4a scans the built APK.

// LessonAssetsValidationTest reads assets/lessons/*.json (+ images) directly through File() at test runtime,
// bypassing resource merging. Gradle does not see them as task inputs → with edits to lessons ONLY,
// testDebugUnitTest is marked UP-TO-DATE and the validator does NOT run (a broken lesson could pass CI). The
// directory is declared an input of all Test tasks: editing a lesson invalidates the cache and the test reruns.
tasks.withType<Test>().configureEach {
  inputs.dir(layout.projectDirectory.dir("src/main/assets/lessons"))
    .withPropertyName("lessonAssets")
    .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
}

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.livedata.ktx)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.coil.compose)
  implementation(libs.coil.svg)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.play.services.location)
  implementation(libs.metrox.viewmodel)
  implementation(libs.metrox.viewmodel.compose)
  implementation(libs.androidx.security.crypto)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.documentfile)
  // Ktor Client Dependencies
  implementation("io.ktor:ktor-client-core:2.3.11")
  implementation("io.ktor:ktor-client-android:2.3.11")
  implementation("io.ktor:ktor-client-content-negotiation:2.3.11")
  implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
  // Markdown rendering (mikepenz multiplatform-markdown-renderer, Material 3 flavor)
  implementation("com.mikepenz:multiplatform-markdown-renderer:0.41.0")
  implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.41.0")
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  // Swaps the Ktor transport in the OpenAiCompatClient tests (fallback along the model chain) — no network.
  testImplementation("io.ktor:ktor-client-mock:2.3.11")
  testImplementation(libs.robolectric)
  testImplementation(libs.androidx.work.testing)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  ksp(libs.androidx.room.compiler)
}
