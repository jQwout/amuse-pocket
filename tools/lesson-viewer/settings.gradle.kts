pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    // Compose Desktop transitively pulls androidx.lifecycle:* — those are on Google Maven, not on Central.
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
  }
}

rootProject.name = "lesson-viewer"
